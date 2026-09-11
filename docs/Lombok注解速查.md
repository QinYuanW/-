# Lombok 注解速查

> 配合「后端代码完全解读」「MyBatis-Plus 注解速查」「Mapper 层整体框架梳理」使用。覆盖本项目实际用到的注解 + 常用进阶注解，每个注解配代码示例和适用场景。

---

## 一、Lombok 是什么

Lombok 是一个**编译期代码生成工具**——你在类上加注解，编译时它自动帮你生成 getter、setter、构造器、日志对象等样板代码，让你的 Java 类更简洁。

> 原理：不是运行时反射，是在 `javac` 编译阶段通过注解处理器（Annotation Processor）直接修改 AST（抽象语法树），生成字节码。所以反编译 class 文件能看到完整方法。

---

## 二、getter / setter 相关（最常用）

### `@Getter` / `@Setter`

| 用法 | 效果 |
|---|---|
| 加在**类**上 | 为所有非静态字段生成 getter / setter |
| 加在**字段**上 | 只为该字段生成 |
| `@Getter(AccessLevel.PROTECTED)` | 指定生成方法的访问级别 |

**项目实例**（`BizException.java`）：
```java
@Getter                          // 只生成 getter，不生成 setter（异常类只读）
public class BizException extends RuntimeException {
    private final Integer code;
    private final String message;
}
```

### `@Data`（最常用，一把梭）

`@Data` = `@Getter` + `@Setter` + `@ToString` + `@EqualsAndHashCode` + `@RequiredArgsConstructor` 的组合。

**项目实例**（`Device.java`）：
```java
@Data
@TableName("device")
public class Device {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String deviceKey;
    private String deviceName;
    // ... 不需要写任何 getter/setter/toString
}
```

> 项目里 7 个实体类 + 11 个 DTO + 2 个配置类 + Result 全部用 `@Data`，一共 21 处。

---

## 三、构造器相关

### `@NoArgsConstructor`
生成**无参构造器**。

> ⚠️ 注意：当类中有 `final` 字段且没有初始化时，无参构造器会编译报错（final 必须赋值）。这时需要配合 `@AllArgsConstructor` 或给 final 字段默认值。

### `@AllArgsConstructor`
生成**全参构造器**（所有字段都作为参数）。

**项目实例**（`LoginVO.java`）：
```java
@Data
@AllArgsConstructor              // 生成全参构造器，方便 new LoginVO(token, user)
public class LoginVO {
    private String token;
    private User user;
}
```

### `@RequiredArgsConstructor`（项目里大量使用，构造器注入的关键）
为**所有 `final` 字段**和**标记了 `@NonNull` 的字段**生成构造器。

这是 Spring **构造器注入**的标准写法——把依赖声明为 `final`，Lombok 自动生成带这些参数的构造器，Spring 自动注入。

**项目实例**（`DeviceController.java`）：
```java
@Slf4j
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor          // 自动生成构造器：public DeviceController(DeviceService deviceService)
public class DeviceController {
    private final DeviceService deviceService;   // final 字段 → 进入构造器
    // 不需要写 @Autowired，Spring 自动通过构造器注入
}
```

> 项目里 5 个 Controller + 5 个 ServiceImpl + 3 个 Config + 1 个 Interceptor + 1 个 Util + 1 个 Client，全部用 `@RequiredArgsConstructor` 做构造器注入，共 16 处。

### 三个构造器注解的对比

| 注解 | 生成什么 | 典型场景 |
|---|---|---|
| `@NoArgsConstructor` | 无参构造器 | JPA/MyBatis 反射创建对象、JSON 反序列化 |
| `@AllArgsConstructor` | 全参构造器 | VO/DTO 快速 new 对象传参 |
| `@RequiredArgsConstructor` | final + @NonNull 字段的构造器 | **Spring 构造器注入**（最推荐） |

> ⚠️ 常见坑：同时用 `@Data`（自带 `@RequiredArgsConstructor`）和 `@AllArgsConstructor` 时，无参构造器会消失。如果框架需要无参构造（如 MyBatis 反射），要手动加 `@NoArgsConstructor`。

---

## 四、日志相关

### `@Slf4j`（项目里大量使用）

在类中自动生成一个 SLF4J 日志对象：
```java
private static final org.slf4j.Logger log =
    org.slf4j.LoggerFactory.getLogger(类名.class);
```

然后直接用 `log.info(...)`、`log.error(...)`。

**项目实例**（`AuthController.java`）：
```java
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginRequest req) {
        log.info("控制台登录请求: username={}", req.getUsername());  // 直接用 log
        return Result.success(authService.login(req));
    }
}
```

> 项目里 5 个 Controller + 5 个 ServiceImpl + JwtInterceptor + EmqxClient + GlobalExceptionHandler，共 13 处用 `@Slf4j`。

### 其他日志注解（项目没用，了解即可）

| 注解 | 生成的日志对象 | 对应日志框架 |
|---|---|---|
| `@Slf4j` | `org.slf4j.Logger` | SLF4J（项目用的） |
| `@Log4j2` | `org.apache.logging.log4j.Logger` | Log4j2 |
| `@CommonsLog` | `org.apache.commons.logging.Log` | Apache Commons Logging |
| `@Log` | `java.util.logging.Logger` | JDK 自带 |
| `@XSlf4j` | `org.slf4j.ext.XLogger` | SLF4J 扩展 |

---

## 五、构建器模式

### `@Builder`
自动生成**建造者模式**（Builder Pattern）的代码。

```java
@Data
@Builder
public class User {
    private Long id;
    private String name;
    private Integer age;
}

// 使用：
User user = User.builder()
    .name("张三")
    .age(20)
    .build();
```

> 项目里没有用 `@Builder`。适合字段多、构造参数复杂的场景，比全参构造器更可读。

### `@SuperBuilder`
`@Builder` 的子类版本——当类有继承关系时，父类用 `@SuperBuilder`，子类也用 `@SuperBuilder`，builder 能链式设置父类字段。

---

## 六、其他常用注解

### `@ToString`
生成 `toString()` 方法。`@Data` 已包含。

常用参数：
- `@ToString(exclude = "password")` — 排除敏感字段
- `@ToString(of = {"id", "name"})` — 只包含指定字段
- `@ToString(callSuper = true)` — 调用父类 toString

### `@EqualsAndHashCode`
生成 `equals()` 和 `hashCode()`。`@Data` 已包含。

> ⚠️ **重要坑**：有继承关系时，`@Data` 默认不调用父类的 equals/hashCode。子类加 `@EqualsAndHashCode(callSuper = true)` 才能正确比较父类字段。

### `@Value`
不可变版本的 `@Data`——所有字段自动变成 `private final`，只生成 getter 不生成 setter，还自带 `@AllArgsConstructor`、`@ToString`、`@EqualsAndHashCode`。

```java
@Value
public class Point {
    int x;    // 自动变成 private final int x
    int y;
}
```

适合 DDD 中的值对象（不可变）。

### `@Accessors(chain = true)`
让 setter 返回 `this`，支持**链式调用**：

```java
@Data
@Accessors(chain = true)
public class User {
    private String name;
    private Integer age;
}

// 使用：
user.setName("张三").setAge(20);   // 链式
```

### `@NonNull`
标记字段/参数不能为空，Lombok 自动在方法开头生成空值检查：

```java
public void setName(@NonNull String name) {
    if (name == null) throw new NullPointerException("name is marked non-null");
    this.name = name;
}
```

### `@Cleanup`
自动关闭资源（调用 `close()`），替代 try-with-resources：

```java
@Cleanup InputStream in = new FileInputStream("file.txt");
@Cleanup OutputStream out = new FileOutputStream("out.txt");
// 方法结束时自动关闭 in 和 out
```

### `@SneakyThrows`
偷偷抛受检异常——把 checked exception 包装成 unchecked 抛出，不用写 `throws`：

```java
@SneakyThrows
public void readFile() {
    new FileInputStream("file.txt");   // FileNotFoundException 不用声明 throws
}
```

> ⚠️ 谨慎使用，会隐藏异常类型，调试时可能困惑。

### `@UtilityClass`
工具类注解——自动把类变成 `final`，构造器私有化，所有方法变成 `static`：

```java
@UtilityClass
public class StringUtils {
    public boolean isEmpty(String s) { return s == null || s.isEmpty(); }
}
// 自动变成：public final class StringUtils { private StringUtils(){} public static boolean isEmpty... }
```

---

## 七、本项目 Lombok 注解使用全景

| 注解 | 使用次数 | 用在哪 | 作用 |
|---|---|---|---|
| `@Data` | 21 | 7 实体 + 11 DTO + 2 配置类 + Result | 一键生成 getter/setter/toString/equals/hashCode |
| `@Slf4j` | 13 | 5 Controller + 5 ServiceImpl + 拦截器 + 客户端 + 异常处理器 | 自动生成日志对象 |
| `@RequiredArgsConstructor` | 16 | 5 Controller + 5 ServiceImpl + 3 Config + 拦截器 + 工具 + 客户端 | 为 final 字段生成构造器（Spring 构造器注入） |
| `@AllArgsConstructor` | 2 | LoginVO、DeviceRegisterVO | 全参构造器，方便快速 new 对象 |
| `@Getter` | 1 | BizException | 异常类只需要 getter（只读） |

---

## 一句话记忆

> **实体/DTO 用 `@Data`、需要日志加 `@Slf4j`、Spring 注入用 `@RequiredArgsConstructor` + final 字段**——这三个掌握了，项目里 95% 的 Lombok 注解就覆盖了。剩下的 `@Builder`（链式构建）、`@Value`（不可变类）、`@Accessors`（链式 setter）属于进阶，用到再查。
