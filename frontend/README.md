# 黑马点评前端

`frontend/hmdp` 是当前项目的前端源码目录，应纳入外层 Java 项目的 Git 版本管理。

当前页面使用 HTML、CSS、Vue 2、Axios 和 Element UI，通过 `<script>` 直接加载，没有 npm、Vite 或 Webpack 构建步骤。

## 本地开发

1. 在 IDEA 中修改 `frontend/hmdp` 下的文件。
2. 执行 `deploy/nginx/sync-frontend.ps1`，把源码同步到本机 Nginx 静态目录。
3. 启动 Spring Boot 和 Nginx。
4. 访问 `http://localhost:8080`，修改页面后使用 `Ctrl + F5` 刷新。

默认同步目标是仓库内现有的：

```text
nginx-1.18.0-hmdp/nginx-1.18.0/html/hmdp
```

如果 Nginx 安装在其他位置，可以传入目标目录：

```powershell
.\deploy\nginx\sync-frontend.ps1 -TargetDirectory "D:\Program\nginx-1.18.0-hmdp\nginx-1.18.0\html\hmdp"
```

同步脚本只覆盖源码中存在的文件，不清理目标目录，因此不会删除运行过程中上传到 `imgs/blogs` 的图片。

## 请求链路

前端 Axios 的基础地址是 `/api`。Nginx 把 `/api/*` 请求转发给 Spring Boot：

```text
浏览器 -> localhost:8080/api/* -> Nginx -> Spring Boot 8081/8082
```

`deploy/nginx/nginx.conf` 保存当前项目所需的 Nginx 配置模板。Nginx 程序、日志、临时文件和运行时上传内容不纳入版本管理。