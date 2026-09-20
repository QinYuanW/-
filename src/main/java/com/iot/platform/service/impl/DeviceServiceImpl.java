package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.common.BizException;
import com.iot.platform.common.ErrorCode;
import com.iot.platform.dto.DeviceRegisterRequest;
import com.iot.platform.dto.DeviceRegisterVO;
import com.iot.platform.dto.DeviceVO;
import com.iot.platform.dto.TelemetryPointVO;
import com.iot.platform.entity.Command;
import com.iot.platform.entity.Device;
import com.iot.platform.entity.DeviceLog;
import com.iot.platform.entity.DeviceTelemetry;
import com.iot.platform.entity.Product;
import com.iot.platform.mapper.CommandMapper;
import com.iot.platform.mapper.DeviceLogMapper;
import com.iot.platform.mapper.DeviceMapper;
import com.iot.platform.mapper.DeviceTelemetryMapper;
import com.iot.platform.mapper.ProductMapper;
import com.iot.platform.service.AuthService;
import com.iot.platform.service.DeviceService;
import com.iot.platform.service.ShadowService;
import com.iot.platform.util.SecretCipher;
import com.iot.platform.ws.TelemetryWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final DeviceMapper deviceMapper;
    private final DeviceTelemetryMapper telemetryMapper;
    private final ProductMapper productMapper;
    private final DeviceLogMapper deviceLogMapper;
    private final CommandMapper commandMapper;
    private final ShadowService shadowService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    /** 用于状态变更后清除认证缓存（F2 启停） */
    private final AuthService authService;
    /** F3：device_secret 加密存储 —— 库中只落密文，明文仅注册响应一次性返回 */
    private final SecretCipher secretCipher;
    /**
     * E2 实时推送：心跳超时置离线时向 WS 订阅者广播 {@code type:"status"}。
     *
     * <p>与上线的广播（TelemetryServiceImpl.refreshOnline）配对 —— 缺了这一侧，
     * 设备列表上的「在线」就只能等下一次重新拉接口才会变回「离线」。
     */
    private final TelemetryWebSocketHandler wsHandler;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter KEY_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 心跳超时阈值（秒）：超过该时间无上报视为离线 */
    private static final long OFFLINE_TIMEOUT_SECONDS = 60;

    @Override
    public Page<DeviceVO> listDevices(int page, int size, String keyword, String productKey,
                                      Integer onlineStatus, Long groupId) {
        Page<Device> devicePage = new Page<>(page, size);
        LambdaQueryWrapper<Device> wrapper = new LambdaQueryWrapper<Device>()
                .orderByDesc(Device::getOnlineStatus)
                .orderByDesc(Device::getLastOnlineAt);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(Device::getDeviceKey, keyword)
                    .or().like(Device::getDeviceName, keyword));
        }
        // E5：按产品筛选（产品可增删后，设备需要能按归属产品区分）
        if (productKey != null && !productKey.isBlank()) {
            wrapper.eq(Device::getProductKey, productKey);
        }
        // 3.3：状态筛选（1 在线 / 2 离线），null 或 0 = 不过滤
        if (onlineStatus != null && onlineStatus > 0) {
            wrapper.eq(Device::getOnlineStatus, onlineStatus == 2 ? 0 : 1);
        }
        // E4：分组筛选 —— groupId=0 表示「未分组」（group_id 为 NULL 或 0）；null = 不过滤
        if (groupId != null) {
            if (groupId == 0L) {
                wrapper.and(w -> w.isNull(Device::getGroupId).or().eq(Device::getGroupId, 0));
            } else {
                wrapper.eq(Device::getGroupId, groupId);
            }
        }
        deviceMapper.selectPage(devicePage, wrapper);

        Page<DeviceVO> voPage = new Page<>(page, size, devicePage.getTotal());
        // ← 每个 Device 实体 → 调 DeviceVO.from() → 变成 DeviceVO 实体
        voPage.setRecords(devicePage.getRecords().stream().map(DeviceVO::from).collect(Collectors.toList()));
        fillProductEnabled(voPage.getRecords());
        return voPage;
    }

    /**
     * 批量填充「所属产品是否启用」。
     *
     * <p>产品状态走 Redis 缓存（{@code prod:enabled:}），且一页设备通常只归属少数几个产品，
     * 故先按 product_key 本地去重，每个产品最多查一次 —— 不构成 N+1。
     *
     * <p>为什么不直接把产品的启用状态并进 device.status：那样会丢掉"是谁停的"这条信息，
     * 前端就只能显示"已停用"，运维得自己猜是设备被停还是产品被停。
     */
    private void fillProductEnabled(List<DeviceVO> vos) {
        if (vos == null || vos.isEmpty()) return;
        Map<String, Boolean> local = new HashMap<>();
        for (DeviceVO vo : vos) {
            vo.setProductEnabled(local.computeIfAbsent(vo.getProductKey(),
                    authService::isProductEnabled));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateStatus(List<String> deviceKeys, int status) {
        if (deviceKeys == null || deviceKeys.isEmpty()) return 0;
        if (status != 0 && status != 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "status 只能是 0（停用）或 1（启用）");
        }

        int n = deviceMapper.update(null, new LambdaUpdateWrapper<Device>()
                .in(Device::getDeviceKey, deviceKeys)
                .set(Device::getStatus, status));

        // 清认证缓存：让「停用」立即生效 —— 否则最长要等 5min TTL，
        // 期间被停设备若重连仍可能被放行（认证走的是缓存里的旧状态）
        for (String k : deviceKeys) {
            authService.evictAuthCache(k);
        }

        log.info("设备状态批量更新为 {}{}，影响 {} 台", status,
                status == 1 ? "（启用）" : "（停用：将拒绝 MQTT 接入）", n);
        return n;
    }

    @Override
    public int deleteDevices(List<String> deviceKeys) {
        if (deviceKeys == null || deviceKeys.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (String deviceKey : deviceKeys) {
            Device device = deviceMapper.selectOne(
                    new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
            if (device == null) {
                continue;
            }
            Long deviceId = device.getId();
            // 事件流表只增不改：删设备时级联清理其遥测/日志/指令，避免孤儿数据
            telemetryMapper.delete(new LambdaQueryWrapper<DeviceTelemetry>().eq(DeviceTelemetry::getDeviceId, deviceId));
            deviceLogMapper.delete(new LambdaQueryWrapper<DeviceLog>().eq(DeviceLog::getDeviceId, deviceId));
            commandMapper.delete(new LambdaQueryWrapper<Command>().eq(Command::getDeviceId, deviceId));
            deviceMapper.deleteById(deviceId);
            // 缓存联动：影子 / 鉴权 / device_key→id 映射
            shadowService.clear(deviceKey);
            // auth: 前缀须与 AuthServiceImpl.CACHE_PREFIX 保持一致（当前 v4），清错 key 会留下幽灵缓存
            redisTemplate.delete(List.of("auth:v4:" + deviceKey, "did:" + deviceKey, "shadow:tsl:" + deviceKey));
            deleted++;
            log.info("device deleted: device_key={}", deviceKey);
        }
        return deleted;
    }

    @Override
    public Page<Map<String, Object>> listDeviceLogs(String deviceKey, int page, int size) {
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        Page<DeviceLog> logPage = new Page<>(page, size);
        Page<Map<String, Object>> voPage = new Page<>(page, size, 0);
        if (device == null) {
            voPage.setRecords(Collections.emptyList());
            return voPage;
        }
        deviceLogMapper.selectPage(logPage, new LambdaQueryWrapper<DeviceLog>()
                .eq(DeviceLog::getDeviceId, device.getId())
                .orderByDesc(DeviceLog::getCreatedAt));
        voPage = new Page<>(page, size, logPage.getTotal());
        voPage.setRecords(logPage.getRecords().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("log_type", l.getLogType());
            m.put("content", l.getContent());
            m.put("created_at", l.getCreatedAt() != null ? l.getCreatedAt().toString() : null);
            return m;
        }).collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public Map<String, Object> getLatestTelemetry(String deviceKey) {
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (device == null) {
            return Collections.emptyMap();
        }
        // 每个属性一行（property_key, value），转成 {key: value} 结构
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map<String, String> row : telemetryMapper.selectLatestByDevice(device.getId())) {
            result.put(row.get("property_key"), row.get("value"));
        }
        return result;
    }

    @Override
    public List<TelemetryPointVO> getTelemetryHistory(String deviceKey, String property, Long start, Long end) {
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (device == null) {
            return Collections.emptyList();
        }

        LocalDateTime startTime = start != null
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(start), ZoneId.of("Asia/Shanghai"))
                : LocalDateTime.now().minusHours(1);
        LocalDateTime endTime = end != null
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(end), ZoneId.of("Asia/Shanghai"))
                : LocalDateTime.now();

        List<DeviceTelemetry> list = telemetryMapper.selectList(
                new LambdaQueryWrapper<DeviceTelemetry>()
                        .eq(DeviceTelemetry::getDeviceId, device.getId())
                        .eq(DeviceTelemetry::getPropertyKey, property)
                        .between(DeviceTelemetry::getTs, startTime, endTime)
                        .orderByAsc(DeviceTelemetry::getTs));

        return list.stream().map(t -> {
            TelemetryPointVO vo = new TelemetryPointVO();
            vo.setTs(t.getTs().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
            vo.setValue(t.getValue());
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getTsl(String deviceKey) {
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (device == null) {
            return Collections.emptyMap();
        }
        Product product = productMapper.selectOne(
                new LambdaQueryWrapper<Product>().eq(Product::getProductKey, device.getProductKey()));
        if (product == null || product.getTsl() == null || product.getTsl().isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(product.getTsl(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("tsl parse error, product_key={}", product.getProductKey(), e);
            return Collections.emptyMap();
        }
    }

    @Override
    public DeviceRegisterVO register(DeviceRegisterRequest request) {
        Product product = requireProduct(request.getProductKey());

        String deviceKey = request.getDeviceKey();
        if (deviceKey == null || deviceKey.isBlank()) {
            deviceKey = generateDeviceKey(request.getNamePrefix());
        }
        if (deviceMapper.selectCount(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceKey, deviceKey)) > 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "device_key 已存在: " + deviceKey);
        }

        String deviceName = request.getDeviceName() != null && !request.getDeviceName().isBlank()
                ? request.getDeviceName() : deviceKey;
        String secret = randomHex(16);

        Device device = new Device();
        // ── ② 从 Request 里拿用户传的字段 ──
        device.setDeviceKey(deviceKey);
        device.setProductKey(product.getProductKey());
        device.setDeviceName(deviceName);

        // ── ③ Service 层自己补充的字段（用户根本没传）──
        // F3：库中只存密文（AES-256-GCM）；明文 secret 仅在本次注册响应里一次性返回
        device.setDeviceSecret(secretCipher.encrypt(secret)); // → 列 device_secret,后端生成的密钥（密文）
        device.setProjectId(product.getProjectId()); // → 列 project_id,从 Product 表里查出来的
        device.setOnlineStatus(0);                  // → 列 online_status,默认离线
        device.setStatus(1);                        // → 列 status,默认正常
        device.setCreatedAt(LocalDateTime.now());    // → 列 created_at,当前时间
        device.setUpdatedAt(LocalDateTime.now());    // → 列 updated_at,当前时间
        deviceMapper.insert(device);                 // → 插入数据库

        log.info("device registered: device_key={}, product_key={}", deviceKey, product.getProductKey());
        return new DeviceRegisterVO(deviceKey, deviceName, secret);
    }

    @Override
    public List<DeviceRegisterVO> registerBatch(DeviceRegisterRequest request) {
        int count = request.getCount() == null ? 1 : request.getCount();
        if (count < 1 || count > 1000) {
            throw new BizException(ErrorCode.PARAM_ERROR, "count 需在 1~1000 之间");
        }
        String prefix = request.getNamePrefix() != null && !request.getNamePrefix().isBlank()
                ? request.getNamePrefix() : "dev";
        String ts = LocalDateTime.now().format(KEY_TS);

        List<DeviceRegisterVO> result = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            DeviceRegisterRequest one = new DeviceRegisterRequest();
            one.setProductKey(request.getProductKey());
            one.setDeviceName(prefix + "_" + i);
            one.setDeviceKey("dev_" + prefix + "_" + ts + "_" + i);
            result.add(register(one));
        }
        log.info("device batch registered: product_key={}, count={}", request.getProductKey(), count);
        return result;
    }

    /**
     * 心跳超时扫描：online=1 且超过 OFFLINE_TIMEOUT_SECONDS 无上报 → 置离线 + 写下线日志
     * 每 30s 执行一次，走 idx_online 索引
     */
    @Scheduled(fixedDelay = 30_000)
    public void offlineScan() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(OFFLINE_TIMEOUT_SECONDS);
        List<Device> stale = deviceMapper.selectList(new LambdaQueryWrapper<Device>()
                .eq(Device::getOnlineStatus, 1)
                .lt(Device::getLastOnlineAt, threshold));
        if (stale.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Device d : stale) {
            deviceMapper.update(null, new LambdaUpdateWrapper<Device>()
                    .set(Device::getOnlineStatus, 0)
                    .set(Device::getLastOfflineAt, now)
                    .set(Device::getUpdatedAt, now)
                    .eq(Device::getId, d.getId())
                    .eq(Device::getOnlineStatus, 1));
            insertLog(d.getId(), 2, "心跳超时离线（> " + OFFLINE_TIMEOUT_SECONDS + "s 无上报）");
            // E2：把「下线」也推给订阅者 —— 否则设备列表的在线状态只能靠重新拉接口纠正
            wsHandler.broadcastStatus(d.getDeviceKey(), 0);
        }
        log.info("offline scan: {} devices -> OFFLINE", stale.size());
    }

    private Product requireProduct(String productKey) {
        Product product = productMapper.selectOne(
                new LambdaQueryWrapper<Product>().eq(Product::getProductKey, productKey));
        if (product == null) {
            throw new BizException(ErrorCode.DEVICE_NOT_FOUND, "产品不存在: " + productKey);
        }
        return product;
    }

    private String generateDeviceKey(String prefix) {
        String base = prefix != null && !prefix.isBlank() ? prefix : "dev";
        return "dev_" + base + "_" + randomHex(3);
    }

    private String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder();
        for (byte b : buf) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void insertLog(Long deviceId, int logType, String content) {
        DeviceLog logEntry = new DeviceLog();
        logEntry.setDeviceId(deviceId);
        logEntry.setLogType(logType);
        logEntry.setContent(content);
        logEntry.setCreatedAt(LocalDateTime.now());
        deviceLogMapper.insert(logEntry);
    }
}