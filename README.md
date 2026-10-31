# jenkins-atom-compress-artifacts

把构建产物打成**一个确定性的 zip**。

compress-artifacts 插件存在的理由很实际：成千上万个小文件归档慢、拷贝也慢。这个原子额外保证两件事：

- **条目按路径排序**，时间戳固定为 1970 → 同样的输入产生同样的字节，两次构建的包可以直接比对，不会因为 mtime 不同而每次都显示变化
- **不会把输出文件打进自己**（递归打包的经典陷阱）

`deleteSource=true` 时打包后删源文件（适合归档后腾空间），日志会写明删了几个。

```bash
./build.sh && ./tools/smoke.sh
```
