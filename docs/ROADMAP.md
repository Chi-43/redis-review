# 项目升级路线：Redis 优化 → 架构升级 → 简历落地

> 目标：把这个「黑马点评」骨架做成一个**能讲透、有数据、能写进简历**的项目。
> 原则：先做深 2~3 个 Redis 场景（能扛住三层追问），再横向加工程化能力，最后才考虑微服务。

---

## 0. 现状盘点

| 类别 | 现状 | 说明 |
|---|---|---|
| 接口层 `controller/` | ✅ 已有 | 用户、店铺、优惠券、秒杀、博客、上传等接口都定义了 |
| 业务层 `service/impl/` | ❌ 全部为空壳 | 10 个实现类只有类声明，`{}` 里没有逻辑 |
| Redis 常量 `utils/RedisConstants` | ✅ 已预留 | 见下表，这 10 个 key 就是升级清单的出处 |
| 基建 | ✅ 已有 | MyBatis-Plus 分页、全局异常、ThreadLocal 存用户、`RedisData`（逻辑过期）、密码加密、正则工具 |
| 数据库 | ✅ `db/hmdp.sql` | 11 张表，含 `tb_seckill_voucher`、`tb_voucher_order`、`tb_sign` |
| 前端 | ❌ 不在本仓库 | 需单独用 Nginx 部署 `hmdp` 静态资源 |

`RedisConstants` 里已经写好的 key 前缀，正好对应要做的事：

```
login:code:      短信验证码         login:token:     登录态
cache:shop:      店铺缓存           cache:shop: + CACHE_NULL_TTL  空值缓存（防穿透）
lock:shop:       分布式锁           seckill:stock:   秒杀库存
blog:liked:      点赞               feed:            关注推送
shop:geo:        附近商铺           sign:            签到
```

---

## 1. 阶段一：用 Redis 把核心链路做出来（6 个模块）

### 1.1 登录与会话（对应 `UserServiceImpl` + 拦截器）

| 步骤 | 做什么 | 关键 API |
|---|---|---|
| 1 | 手机号 → 验证码存 `login:code:{phone}`，TTL 2 分钟 | `SETEX` |
| 2 | 校验通过 → 生成 token（UUID），`UserDTO` 存 `login:token:{token}` 作为 Hash | `putAll` + `expire` |
| 3 | 拦截器统一校验 token，刷新 TTL 实现**滑动过期**；用户信息放 `UserHolder`（ThreadLocal），`afterCompletion` 中 `remove()` 防泄漏 | `expire` |
| 4 | 登出直接删 key，天然让 token 失效 | `DELETE` |

**验收**：不带 token 访问 `/user/me` 返回 401；带 token 连续访问 20 分钟不掉线；重启服务不掉线（说明无状态）。
**简历句**：*基于 Redis 实现无状态登录态，token 滑动续期，支持服务多实例水平扩展、登出即时失效。*
**面试追问**：为什么不用 Session？ThreadLocal 在异步线程里为什么会丢用户？（→ 秒杀异步下单时踩到，见 1.3）

### 1.2 缓存体系（对应 `ShopTypeServiceImpl`、`ShopServiceImpl`）

这是简历里最值钱的一块，**四个层次按顺序做**：

1. **缓存类型列表**：`shopType` 整个 List 存 String（JSON），一次 `GET` 顶掉一次关联查询。
2. **缓存商铺详情**：`cache:shop:{id}` 存 JSON，TTL 30 分钟。
3. **防穿透**：查不到时写一个空值对象，TTL 2 分钟（`CACHE_NULL_TTL`）；进阶用**布隆过滤器**。
4. **防击穿**：
   - 方案 A：互斥锁。`SETNX lock:shop:{id}` + TTL，抢不到锁的线程 `sleep` 重试，释放时用 **Lua 脚本比对 value 再删**（防误删别人的锁）。
   - 方案 B：逻辑过期。value 里带 `expireTime`，命中即返回；异步线程重建缓存（`RedisData` 已经准备好了）。
5. **防雪崩**：TTL 加随机值；Redis 集群/哨兵；接口限流兜底。

**验收**：JMeter 用 1000 并发打同一个不存在的 id，MySQL 的 QPS 不上升（穿透被挡住）；缓存失效瞬间，DB 只收到 1 次查询（击穿被挡住）。
**简历句**：*实现空值缓存 + 互斥锁重建 + TTL 随机化三件套，压测下缓存命中率 95%+，热点 key 失效时 DB 峰值 QPS 下降约 90%。*
**面试追问**：布隆过滤器的误判怎么处理？逻辑过期会不会返回脏数据？为什么删锁要用 Lua？

### 1.3 秒杀下单（对应 `VoucherOrderServiceImpl`，最核心）

现状是同步下单：查库存 → 扣库存 → 写订单，三个人同时来就超卖。

| 版本 | 做法 | 解决什么 |
|---|---|---|
| v1 | 乐观锁扣库存：`update tb_seckill_voucher set stock = stock - 1 where stock > 0` | 超卖 |
| v2 | 加 `user_id` 唯一索引 + 先查后插 | 一人一单（并发下仍可能重复，需要锁） |
| v3 | **Lua 脚本原子执行**：判断库存 + 判断是否已下单 + 扣库存，一次网络往返 | 把「查-改」变成原子操作，避免并发窗口 |
| v4 | **异步下单**：Lua 通过后立刻返回订单号，下单信息投递到阻塞队列，后台线程串行写库 | 接口 RT 从几十 ms 降到 <10ms，抗住瞬时流量 |
| v5 | 队列换成 **Redis Stream / RabbitMQ**，加手动 ACK、死信队列、消费幂等 | 进程重启不丢单 |

代码落点：`VoucherOrderServiceImpl` + 新建 `seckill.lua`（放 `resources/`），后台线程用 `@PostConstruct` 启动。
**验收**：JMeter 1000 线程抢 100 张券，`tb_voucher_order` 正好 100 条，无超卖；同一用户并发 50 次只成 1 单。
**简历句**：*秒杀链路改为 Lua 原子校验 + 异步队列落库，接口 RT 从 x ms 降至 y ms，压测 1000 并发下订单数精确等于库存，零超卖。*
**面试追问**：Lua 为什么能保证原子性？异步下单失败了怎么办？队列满了呢？怎么保证消息不丢不重？

### 1.4 社交互动（对应 `BlogServiceImpl`、`FollowServiceImpl`）

- **点赞**：`blog:liked:{id}` 用 **Set** 存点赞用户（`SADD` 天然去重），**ZSet** 按时间排序用于"谁点赞了我"。点赞数 = `SCARD`，不必回查 DB。
- **关注 + 共同关注**：`follow:{userId}` 用 Set，共同关注 = `SINTER`。
- **关注推送（Feed）**：推模式，发帖时 `ZADD feed:{fanId} timestamp blogId` 给每个粉丝；读的时候 `ZREVRANGEBYSCORE` 做**滚动分页**（`max=上次的 score`，`offset` 处理同分）。

**简历句**：*基于 Set/ZSet 实现点赞与关注流，点赞数 O(1) 查询，共同关注用集合交集一次算完；Feed 用 ZSet 游标分页，避免深分页。*
**面试追问**：推模式大 V 有几千万粉丝怎么办？（→ 混合模式：活跃粉丝推、不活跃拉）

### 1.5 统计与位置（对应 `ShopTypeServiceImpl`/新模块）

- **附近商铺**：`shop:geo:{typeId}` 用 `GEOADD` 写入店铺坐标，查询用 `GEOSEARCH` 按距离排序 + 分页。
- **签到**：`sign:{userId}:{yyyyMM}` 用 **Bitmap**，第 N 天 = 第 N 位；连续签到用位运算或 `BITFIELD`；签到天数 = `BITCOUNT`。
- **UV/PV**：**HyperLogLog** 估算 UV（误差 0.81%），PV 用 `INCR`。
- **排行榜**：ZSet 存热度，`ZREVRANGE` 取 Top N。

**简历句**：*用 GEO 实现按距离找店（GEOSEARCH 一次查询完成过滤+排序），Bitmap 存签到（一个用户一个月仅占约 4KB），HyperLogLog 做 UV 去重，内存省 90%。*
**面试追问**：Bitmap 存 10 亿用户行不行？（→ 见 1.6 的 Redis 容量认知）HyperLogLog 为什么不能取具体人数？

### 1.6 稳定性（分布式锁 + 限流）

- 自研 `SETNX` 锁有**超时误删**和**不可重入**问题 → 换 **Redisson**（看门狗自动续期、可重入、`tryLock` 带等待时间）。
- 接口限流：固定窗口 / 滑动窗口（ZSet）/ 令牌桶（Lua），或 `RedissonClient.getRateLimiter`。
- 顺带补齐：`bigkey` / 热 key 排查、`maxmemory-policy` 选择、RDB/AOF 取舍 —— 这些是面试里区分"用过"和"懂"的地方。

---

## 2. 阶段二：Redis 之外的升级（按性价比排序）

### P0 — 一周内能出效果，简历立刻变厚

| 项 | 怎么做 | 简历价值 |
|---|---|---|
| **Docker Compose 一键起环境** | `docker-compose.yml` 编排 mysql + redis + nginx，挂载 `db/hmdp.sql` 初始化 | 展示工程化，面试官可直接跑起来 |
| **GitHub Actions CI** | push 触发 `mvn -B verify`，缓存 `~/.m2` | 有 CI 徽章，说明你懂流水线 |
| **JMeter 压测报告** | 每个优化点前后各压一次，记录 QPS / P95 / 错误率 | **简历里所有数字的唯一合法来源** |
| **接口文档** | 引入 springdoc-openapi，`/swagger-ui.html` | 交付完整度 |
| **参数校验 + 统一响应** | `@Valid` + `@RestControllerAdvice` | 代码规范性 |

### P1 — 核心亮点，值得花 2~3 周

1. **MQ 异步下单**（RabbitMQ）：替代阻塞队列，补齐生产者确认、手动 ACK、死信队列、消费幂等（唯一索引兜底）。
2. **缓存一致性**：改库后延迟双删 + `Canal` 监听 binlog 投递 MQ 删缓存，讲清"为什么不能先删缓存再更新库"。
3. **Sentinel / Resilience4j**：对下单、短信等外部依赖做熔断降级，配一个"依赖挂了接口仍可用"的演示。
4. **可观测性**：Micrometer + Prometheus + Grafana 面板（QPS、RT、缓存命中率、连接池），截图放 README。
5. **单测 + Testcontainers**：给秒杀 Lua、缓存重建写测试，覆盖率 60%+。

### P2 — 进阶（时间够再上，讲清取舍比做完更重要）

- **多级缓存**：Caffeine 本地 + Redis，用 MQ 广播失效；重点讲"本地缓存一致性怎么保证"。
- **分库分表**：ShardingSphere 按 `user_id` 分片，`tb_voucher_order` 深分页改游标。
- **读写分离**：主从 + `AbstractRoutingDataSource`。
- **微服务拆分**：Nacos + Gateway + OpenFeign + Seata。单人项目拆 4 个服务大概率得不偿失，**面试讲清"我评估过，暂不拆"反而是加分项**。
- **K8s 部署**：Deployment + Service + ConfigMap。

---

## 3. 阶段三：怎么写成简历条目

### 公式

```
业务背景（做什么） + 技术手段（怎么做的） + 量化结果（快了多少 / 扛了多少）
```

一条 bullet 只讲一件事，3~4 条足够，**每条都必须能被追问 3 层**。

### 可直接改的模板（`x/y` 用你自己的压测数据填）

- 基于 Redis 重构缓存链路，落地空值缓存、互斥锁重建、TTL 随机化，商铺详情接口 P95 由 `x ms` 降至 `y ms`，缓存命中率 `95%`，DB QPS 下降约 `90%`。
- 秒杀链路改为 Lua 原子校验 + 异步队列落库，接口 RT 由 `x ms` 降至 `y ms`，`1000` 并发下订单数精确等于库存，无超卖、无一单多卖。
- 使用 Redisson 替换自研 SETNX 锁，解决锁超时误删与不可重入问题，下单链路死锁率为 0。
- 基于 Set/ZSet 实现点赞与关注流，点赞数 O(1) 查询；Feed 采用 ZSet 游标分页，规避深分页性能衰减。
- 基于 GEO / Bitmap / HyperLogLog 实现附近商铺、签到、UV 统计，单用户月度签到占用约 4KB，UV 统计内存下降 `90%`。
- 接入 GitHub Actions + Docker Compose，实现一键起环境与构建校验，PR 平均反馈时间 `x 分钟`。

### 数据从哪来（别编）

| 指标 | 来源 |
|---|---|
| QPS / RT / 错误率 | JMeter 聚合报告（优化前后各跑一次） |
| 缓存命中率 | `redis-cli info stats` → `keyspace_hits / (hits+misses)` |
| DB 压力下降 | MySQL `slow_query_log` + `SHOW GLOBAL STATUS LIKE 'Questions'` |
| 内存占用 | `redis-cli info memory` |

### 反模式（面试官一眼看穿）

- ❌ 堆名词："精通 Redis、Kafka、K8s、微服务"，一问原理就露馅。
- ❌ 把课程里的实现原样写成"我优化了"——教程痕迹（`@author 虎哥`、注释）要先清掉。
- ❌ 没有对比数据却写"性能提升 10 倍"。
- ❌ 只写"用了 XX"，不写"解决了什么问题"。

### 高频追问清单（做完一遍自测）

1. 缓存穿透/击穿/雪崩的区别，你各自怎么解的？
2. 布隆过滤器误判率怎么控制？删除元素怎么办？
3. 逻辑过期返回旧数据，业务能接受吗？如何保证最终一致？
4. 为什么删锁必须用 Lua？SETNX + EXPIRE 两条命令有什么问题？
5. Redisson 看门狗原理？锁续期失败会怎样？
6. Lua 脚本执行时 Redis 是单线程吗？脚本执行时间长会怎样？
7. 异步下单：消息丢了、重复消费、消费失败怎么处理？
8. 一人一单在集群下半步锁失效怎么办？
9. Bitmap 的 offset 上限？超大 offset 有什么风险？
10. HyperLogLog 的误差来源？为什么不能用 `PFCOUNT` 取明细？

---

## 4. 执行节奏（4 周建议）

| 周 | 内容 | 产出 |
|---|---|---|
| W1 | 登录态 + 缓存体系（1.1 / 1.2） | 2 个 commit + 压测对比表 |
| W2 | 秒杀全链路（1.3）+ 分布式锁（1.6） | 超卖测试报告 + Lua 脚本 |
| W3 | 社交互动 + 统计位置（1.4 / 1.5）+ MQ 升级 | 功能截图 + 架构图 |
| W4 | 工程化（Docker / CI / 压测 / 文档）+ 简历打磨 | README 完善 + 压测报告 |

### Git 工作流（让 commit 历史本身成为作品）

- 每个功能一个分支：`feat/seckill-lua`、`perf/shop-cache`，合并前自测；
- 提交信息用约定式：`feat:`、`fix:`、`perf:`、`docs:`、`test:`；
- 每个优化点在 `docs/` 下留一篇短文档：**问题 → 方案对比 → 实测数据 → 遗留问题**，面试前翻一遍就是复习。

### 最终交付物清单

- [ ] README：架构图（mermaid 即可）+ 功能列表 + 压测数据表 + 启动方式
- [ ] `docs/`：每个 Redis 场景一篇设计文档
- [ ] 压测报告：优化前后对比（JMeter HTML 报告截图）
- [ ] GitHub Actions 绿色徽章
- [ ] 一张能讲 5 分钟的架构图

---

## 5. 取舍提醒

- **深度 > 广度**：2 个场景做到能画出时序图、能说出失败场景，比 10 个场景都是"用了 Redis"强得多。
- **先有数据，再写简历**：没有压测数字就写"优化了性能"，等于给自己挖坑。
- **教程痕迹要清干净**：包名/类名/注释、`@author`、README 里的课程用语，都要换成自己的表述。
- **微服务不是必选项**：单体 + Redis + MQ + 可观测性，对一个业务量不大的点评类应用是合理架构，讲清这个判断本身就是亮点。
