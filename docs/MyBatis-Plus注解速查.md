# MyBatis-Plus 注解速查

> 配合「后端代码完全解读」使用。覆盖本项目实际用到的注解 + 常用进阶注解，每个注解配代码示例和适用场景。

---

## 一、实体类与表注解

| 注解 | 作用 | 本项目例子 |
|---|---|---|
| `@TableName("表名")` | 实体类映射到哪张表。类名和表名不一致时必须写 | `@TableName("device")` |
| `@TableName(autoResultMap = true)` | 开启自动结果映射（JSON 字段、类型处理器需要） | 本项目 tsl/payload 是 JSON 但没开，直接用 String 接 |

---

## 二、主键注解

```java
@TableId(type = IdType.AUTO)
private Long id;
```

`@TableId` 标记主键字段，`type` 指定主键生成策略：

| IdType | 含义 | 适用场景 |
|---|---|---|
| `AUTO` | 数据库自增（AUTO_INCREMENT） | MySQL 自增主键，本项目全用这个 |
| `NONE` | 无状态，未设置（跟随全局） | 默认值 |
| `INPUT` | 用户自己输入 ID | 手动指定主键 |
| `ASSIGN_ID` | 雪花算法自动生成 Long 型 ID | 分布式系统、分库分表 |
| `ASSIGN_UUID` | 自动生成 UUID（去掉横线） | 字符串主键 |

> 本项目 7 个实体类全部用 `@TableId(type = IdType.AUTO)`，对应数据库 `AUTO_INCREMENT`。

---

## 三、字段注解（最常用、最容易忘）

| 注解 | 作用 | 典型用法 |
|---|---|---|
| `@TableField("列名")` | 字段映射到哪一列。驼峰转下划线全局开了就不用写 | `@TableField("device_key")` |
| `@TableField(exist = false)` | **这个字段不是数据库列**，查询/插入时忽略 | 实体类里加个临时计算字段 |
| `@TableField(select = false)` | 查询时不查这个字段（比如大字段、敏感字段） | 密码哈希字段不想被查出 |
| `@TableField(fill = FieldFill.INSERT)` | **插入时自动填充**（配合 MetaObjectHandler） | createdAt 自动填当前时间 |
| `@TableField(fill = FieldFill.INSERT_UPDATE)` | 插入和更新时都自动填充 | updatedAt 自动刷新 |
| `@TableField(condition = ...)` | 自定义查询条件拼接方式 | 模糊查询等特殊场景 |
| `@TableLogic` | 逻辑删除标记 | 本项目实体表的 deleted 字段 |
| `@Version` | 乐观锁标记（更新时自动加 `AND version=?`） | 并发更新防覆盖 |
| `@EnumValue` | 枚举类里标记哪个字段存数据库 | 状态枚举用数字存库 |

### 自动填充（FieldFill）补充

本项目的 `createdAt`、`updatedAt` 是**手动 set 的**（比如 `device.setCreatedAt(LocalDateTime.now())`），没有用自动填充。更优雅的写法是配一个 `MetaObjectHandler`：

```java
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
```

然后实体字段加 `@TableField(fill = FieldFill.INSERT)`，插入时框架自动填时间，不用每次手动 set。

---

## 四、Mapper 与全局注解

| 注解 | 位置 | 作用 |
|---|---|---|
| `@Mapper` | Mapper 接口上 | 标记这是 MyBatis 的 Mapper（本项目 7 个 Mapper 都有） |
| `@MapperScan("包路径")` | 启动类上 | 批量扫描 Mapper 接口，有了它其实 `@Mapper` 可以不写 |
| `@Select` / `@Insert` 等 | Mapper 方法上 | 手写 SQL（本项目只有 `DeviceTelemetryMapper.selectLatestByDevice` 用了） |

> 注意：本项目启动类写了 `@MapperScan("com.iot.platform.mapper")`，同时每个 Mapper 接口又写了 `@Mapper`——**两者重复了**，留一个就行。`@MapperScan` 更推荐（不用每个接口都加注解）。

---

## 五、本项目注解使用全景（速查）

```java
@Data                        // Lombok，不是 MP
@TableName("device")         // MP：表名映射
public class Device {
    @TableId(type = IdType.AUTO)   // MP：主键自增
    private Long id;
    private String deviceKey;      // MP：靠全局 map-underscore-to-camel-case 自动映射
    @TableLogic                   // MP：逻辑删除
    private Integer deleted;
    private LocalDateTime createdAt;
}
```

```java
@Mapper                        // MP：标记 Mapper（和 @MapperScan 重复了）
public interface DeviceMapper extends BaseMapper<Device> { }
```

---

## 一句话记忆

> **`@TableName` 找表、`@TableId` 定主键、`@TableField` 管字段、`@TableLogic` 做软删、`@MapperScan` 扫接口**——这五个掌握了，MyBatis-Plus 的注解就够用了。剩下的 `@Version`（乐观锁）、`@EnumValue`（枚举）、自动填充属于进阶，用到再查。
