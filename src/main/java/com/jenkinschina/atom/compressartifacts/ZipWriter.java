package com.jenkinschina.atom.compressartifacts;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes a zip by hand, because {@code ZipOutputStream} cannot write what this step needs.
 *
 * <p>{@code ZipOutputStream} decides the compression itself, so for deflated entries it must put the sizes in a
 * trailing data descriptor (general purpose flag 0x8), and it adds an extended-timestamp extra field as soon as a
 * modification time is set. Apple's {@code unzip} reads both badly: the archive looks truncated and UTF-8 names come
 * out as mojibake. Writing the container directly gives every entry a complete local header, no extra fields, and the
 * UTF-8 name flag where it belongs, so the archive opens the same way everywhere.
 */
final class ZipWriter implements Closeable {

    private static final int LOCAL_HEADER = 0x04034b50;
    private static final int CENTRAL_HEADER = 0x02014b50;
    private static final int END_OF_CENTRAL = 0x06054b50;
    /** Bit 11: entry names are UTF-8. */
    private static final int UTF8_FLAG = 1 << 11;
    private static final int DEFLATED = 8;
    /** 1980-01-01 00:00, the earliest time the format can express: fixed, so two builds produce the same bytes. */
    private static final int DOS_TIME = 0;
    private static final int DOS_DATE = (1 << 5) | 1;   // year 1980, month 1, day 1

    private final OutputStream out;
    private final List<Record> records = new ArrayList<>();
    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    private long written;

    ZipWriter(OutputStream out) {
        this.out = out;
    }

    /** Adds one file, deflating it first so the local header can carry the real sizes. */
    void add(Path file, String entryName) throws IOException {
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        if (name.length > 0xFFFF) {
            throw new IOException("entry name too long for the zip format: " + entryName);
        }
        long offset = written;

        CRC32 crc = new CRC32();
        Path compressed = Files.createTempFile("atom-zip-", ".part");
        try {
            long uncompressedSize = deflate(file, compressed, crc);
            long compressedSize = Files.size(compressed);

            ByteArrayOutputStream header = new ByteArrayOutputStream();
            putInt(header, LOCAL_HEADER);
            putShort(header, 20);              // version needed
            putShort(header, UTF8_FLAG);
            putShort(header, DEFLATED);
            putShort(header, DOS_TIME);
            putShort(header, DOS_DATE);
            putInt(header, (int) crc.getValue());
            putInt(header, (int) compressedSize);
            putInt(header, (int) uncompressedSize);
            putShort(header, name.length);
            putShort(header, 0);               // no extra field: nothing for a strict reader to trip over
            header.write(name);
            byte[] headerBytes = header.toByteArray();
            out.write(headerBytes);
            written += headerBytes.length;

            try (InputStream in = Files.newInputStream(compressed)) {
                written += in.transferTo(out);
            }
            records.add(new Record(name, (int) crc.getValue(), (int) compressedSize, (int) uncompressedSize, offset));
        } finally {
            Files.deleteIfExists(compressed);
        }
    }

    /** {@inheritDoc} Writes the central directory, then the end record. */
    @Override
    public void close() throws IOException {
        try {
            long centralStart = written;
            for (Record record : records) {
                ByteArrayOutputStream header = new ByteArrayOutputStream();
                putInt(header, CENTRAL_HEADER);
                putShort(header, (3 << 8) | 20);   // made by Unix: FAT-made archives make some unzip builds mangle names
                putShort(header, 20);              // version needed
                putShort(header, UTF8_FLAG);
                putShort(header, DEFLATED);
                putShort(header, DOS_TIME);
                putShort(header, DOS_DATE);
                putInt(header, record.crc());
                putInt(header, record.compressedSize());
                putInt(header, record.uncompressedSize());
                putShort(header, record.name().length);
                putShort(header, 0);               // extra length
                putShort(header, 0);               // comment length
                putShort(header, 0);               // disk number
                putShort(header, 0);               // internal attributes
                putInt(header, (0100644 << 16));   // a normal file (rw-r--r--): Unix-made archives are read as Unix modes
                putInt(header, (int) record.offset());
                header.write(record.name());
                byte[] bytes = header.toByteArray();
                out.write(bytes);
                written += bytes.length;
            }
            long centralSize = written - centralStart;

            ByteArrayOutputStream end = new ByteArrayOutputStream();
            putInt(end, END_OF_CENTRAL);
            putShort(end, 0);                      // this disk
            putShort(end, 0);                      // disk with the central directory
            putShort(end, records.size());
            putShort(end, records.size());
            putInt(end, (int) centralSize);
            putInt(end, (int) centralStart);
            putShort(end, 0);                      // comment length
            out.write(end.toByteArray());
        } finally {
            deflater.end();
            out.close();
        }
    }

    /** Compresses one file into {@code target}, updating the CRC; returns the uncompressed length. */
    private long deflate(Path file, Path target, CRC32 crc) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        byte[] output = new byte[64 * 1024];
        long size = 0;
        try (InputStream in = Files.newInputStream(file);
                OutputStream sink = Files.newOutputStream(target)) {
            deflater.reset();
            int read;
            while ((read = in.read(buffer)) > 0) {
                crc.update(buffer, 0, read);
                size += read;
                deflater.setInput(buffer, 0, read);
                while (!deflater.needsInput()) {
                    int produced = deflater.deflate(output);
                    if (produced > 0) {
                        sink.write(output, 0, produced);
                    }
                }
            }
            deflater.finish();
            while (!deflater.finished()) {
                int produced = deflater.deflate(output);
                if (produced > 0) {
                    sink.write(output, 0, produced);
                }
            }
        }
        return size;
    }

    private static void putInt(ByteArrayOutputStream target, int value) {
        target.write(value & 0xff);
        target.write((value >> 8) & 0xff);
        target.write((value >> 16) & 0xff);
        target.write((value >> 24) & 0xff);
    }

    private static void putShort(ByteArrayOutputStream target, int value) {
        target.write(value & 0xff);
        target.write((value >> 8) & 0xff);
    }

    private record Record(byte[] name, int crc, int compressedSize, int uncompressedSize, long offset) {}
}
