# Mapper 层整体框架梳理

> 配合「后端代码完全解读」和「MyBatis-Plus 注解速查」使用。从底层到上层，把 Mapper 层的完整结构串起来。

---

## 一、框架组成（四层结构）

```
┌─────────────────────────────────────────────────────────────────┐
│  第1层：启动入口                                                  │
│  IotPlatformApplication.java                                    │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ @MapperScan("com.iot.platform.mapper")                   │    │
│  │   → 告诉 Spring：去这个包下找所有 Mapper 接口              │    │
│  └─────────────────────────────────────────────────────────┘    │
└──────────────────────────────┬──────────────────────────────────┘
                               │ 扫描到每个 Mapper 接口
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  第2层：Mapper 接口（你的代码）                                   │
│  mapper/DeviceMapper.java                                       │
│  mapper/UserMapper.java                                         │
│  mapper/CommandMapper.java  ...（共7个）                         │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ @Mapper                                                  │    │
│  │ public interface DeviceMapper extends BaseMapper<Device> │    │
│  │                                                          │    │
│  │  ✅ extends BaseMapper<T> → 继承通用 CRUD 方法签名         │    │
│  │  ✅ @Mapper 或被 @MapperScan 扫描到                        │    │
│  └─────────────────────────────────────────────────────────┘    │
└──────────────────────────────┬──────────────────────────────────┘
                               │ MyBatis-Plus 启动时处理
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  第3层：MyBatis-Plus 自动生成（你不写，框架生成）                  │
│                                                                 │
│  BaseMapper<T> 内置的方法签名：                                   │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ int insert(T entity)                // 新增               │    │
│  │ T selectById(Serializable id)        // 按主键查          │    │
│  │ T selectOne(Wrapper<T> wrapper)      // 条件查一条        │    │
│  │ List<T> selectList(Wrapper<T>)       // 条件查多条        │    │
│  │ Page<T> selectPage(Page<T>, Wrapper<T>) // 分页查         │    │
│  │ int updateById(T entity)             // 按主键更新        │    │
│  │ int update(null, Wrapper<T>)         // 条件更新          │    │
│  │ int deleteById(Serializable id)      // 按主键删          │    │
│  │ int delete(Wrapper<T>)               // 条件删            │    │
│  │ long selectCount(Wrapper<T>)         // 统计数量          │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                 │
│  这些方法在运行时被自动生成实现体 → 拼 SQL → 执行 → 返回结果       │
└──────────────────────────────┬──────────────────────────────────┘
                               │ 执行 SQL
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  第4层：配置 & 插件（MybatisPlusConfig.java）                    │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ @Configuration                                           │    │
│  │ public class MybatisPlusConfig {                          │    │
│  │     @Bean                                                 │    │
│  │     public MybatisPlusInterceptor mybatisPlusInterceptor(){ │    │
│  │         // 注册分页插件                                   │    │
│  │         new PaginationInnerInterceptor(DbType.MYSQL);     │    │
│  │     }                                                     │    │
│  │ }                                                         │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

---

## 二、每个 Mapper 接口对应关系

| Mapper 接口 | 继承自 | 操作哪个 Entity | 对应数据库表 |
|---|---|---|---|
| `UserMapper` | `BaseMapper<User>` | `User` | `user` |
| `DeviceMapper` | `BaseMapper<Device>` | `Device` | `device` |
| `CommandMapper` | `BaseMapper<Command>` | `Command` | `command` |
| `ProductMapper` | `BaseMapper<Product>` | `Product` | `product` |
| `ProjectMapper` | `BaseMapper<Project>` | `Project` | `project` |
| `DeviceLogMapper` | `BaseMapper<DeviceLog>` | `DeviceLog` | `device_log` |
| `DeviceTelemetryMapper` | `BaseMapper<DeviceTelemetry>` | `DeviceTelemetry` | `device_telemetry` |

---

## 三、运行时流程（启动时发生了什么）

```
Spring Boot 启动
    │
    ▼
IotPlatformApplication.main() 启动
    │
    ▼
扫描 @MapperScan("com.iot.platform.mapper")
    │
    ├─▶ 找到 DeviceMapper → 生成代理对象 → 注册到容器
    ├─▶ 找到 UserMapper   → 生成代理对象 → 注册到容器
    ├─▶ 找到 CommandMapper → 生成代理对象 → 注册到容器
    └─▶ ... 共7个
    │
    ▼
Service 层注入：
    private final DeviceMapper deviceMapper;  ✅ 注入成功
    │
    ▼
调用：
    deviceMapper.insert(device);
        │
        ▼
MyBatis-Plus 自动：
    ① 看实体 → Device → 表名 device
    ② 看字段 → deviceKey, deviceName... → 列名 device_key, device_name...
    ③ 拼 SQL：INSERT INTO device (device_key, device_name, ...) VALUES (?, ?, ...)
    ④ 执行 SQL，返回结果
```

---

## 四、一句话总结整个框架

> **你只写 3 样东西，剩下全是 MyBatis-Plus 帮你干：**
> 1. **MapperScan** 告诉它去哪找接口
> 2. **Mapper 接口** 继承 BaseMapper 声明"我要 CRUD"
> 3. **Entity** 加 @TableName 告诉它表名映射
>
> 然后它自动生成代理实现、自动拼 SQL、自动映射字段、自动执行。**你一行 SQL 都不用写。**
