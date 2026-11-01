# redis-review

基于 Spring Boot 的「黑马点评」后端项目，主要用于 Redis 实战练习：缓存、缓存穿透/击穿、分布式锁、秒杀异步下单、点赞与关注（Set / ZSet / HyperLogLog / GEO）。

## 技术栈

| 组件 | 版本 |
|---|---|
| JDK | 1.8 |
| Spring Boot | 2.3.12.RELEASE |
| MyBatis-Plus | 3.4.3 |
| MySQL | 5.1.47 驱动 |
| Redis | spring-boot-starter-data-redis + Lettuce 连接池 |
| Hutool | 5.7.17 |
| Lombok | - |

## 启动步骤

### 1. 准备数据库

```bash
mysql -u root -p -e "create database hmdp default charset utf8mb4;"
mysql -u root -p hmdp < src/main/resources/db/hmdp.sql
```

### 2. 配置文件

仓库里只有模板 `src/main/resources/application.yaml.example`，真实的 `application.yaml` 被 `.gitignore` 排除（含密码，不上传）。

```bash
cd src/main/resources
cp application.yaml.example application.yaml
```

然后把里面的 `<你的 MySQL 密码>`、`<你的 Redis 地址>` 换成真实值。

### 3. 改上传目录（可选）

`com.hmdp.utils.SystemConstants` 里的 `IMAGE_UPLOAD_DIR` 原本指向课程环境的 Nginx 目录，需按自己的部署路径调整：

```java
public static final String IMAGE_UPLOAD_DIR = "D:\\lesson\\nginx-1.18.0\\html\\hmdp\\imgs\\";
```

### 4. 启动

```bash
mvn spring-boot:run
```

默认端口 `8081`。前端页面需要单独用 Nginx 部署 `hmdp` 静态资源并反向代理到该端口。

## 目录结构

```
src/main/java/com/hmdp
├── config/      MyBatis-Plus 分页插件、全局异常处理
├── controller/  店铺、优惠券、博客、关注、订单、上传等接口
├── service/     业务接口与实现（缓存、秒杀、点赞等核心逻辑在此）
├── mapper/      MyBatis-Plus Mapper
├── entity/      数据库实体
├── dto/         Result、UserDTO 等传输对象
└── utils/       常量、正则、密码加密、ThreadLocal 用户上下文
```

## 约定

- 只在本地维护的真实配置（`application*.yaml`）、IDE 目录（`.idea/`）、构建产物（`target/`）、日志和 Redis 落盘文件不入库。
- 如确需提交真实配置，用 `git add -f` 强制添加，并确认其中不含敏感信息。
