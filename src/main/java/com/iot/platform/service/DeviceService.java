package com.iot.platform.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iot.platform.dto.DeviceRegisterRequest;
import com.iot.platform.dto.DeviceRegisterVO;
import com.iot.platform.dto.DeviceVO;
import com.iot.platform.dto.TelemetryPointVO;

import java.util.List;
import java.util.Map;

public interface DeviceService {
    /**
     * 分页查询设备列表
     * @param page 页码
     * @param size 每页数量
     * @param keyword 搜索关键词
     * @param productKey 产品标识筛选；null/空白 = 不过滤（E5）
     * @param onlineStatus 在线状态筛选：null/0 不过滤，1 在线，2 离线（3.3 列表增强）
     * @param groupId 分组筛选：null 不过滤，0 = 未分组，&gt;0 = 指定分组（E4）
     * @param sort 排序字段（评审 4.2）：{@code last_online_at}（默认）/ {@code device_key} /
     *             {@code device_name} / {@code created_at}；**白名单外一律回退默认**，
     *             不把用户输入直接拼进 SQL
     * @param order 排序方向 {@code asc} / {@code desc}；缺省按字段选一个合理的默认
     * @return 设备列表
     */
    Page<DeviceVO> listDevices(int page, int size, String keyword, String productKey,
                               Integer onlineStatus, Long groupId, String sort, String order);

    /**
     * 修改设备名称（评审 4.8）。
     *
     * <p>只改名称 —— 启停用 {@link #updateStatus}（保留全部数据、可一键恢复），
     * 删除用 {@link #deleteDevices}（不可逆），三者语义不同，不要混在一个入口里。
     *
     * @param deviceKey 设备标识
     * @param deviceName 新名称（去首尾空白；空白视为不改）
     * @return 是否实际更新
     */
    boolean renameDevice(String deviceKey, String deviceName);

    /**
     * 批量删除设备（级联清理遥测/日志/指令/影子，3.3 列表增强）
     * @param deviceKeys 待删除设备标识列表
     * @return 实际删除数量
     */
    int deleteDevices(List<String> deviceKeys);

    /**
     * F2 批量启停设备。
     *
     * @param deviceKeys 设备标识列表（单个设备传长度为 1 的列表）
     * @param status 1 = 启用，0 = 停用
     * @return 实际影响的设备数
     *
     * <p>停用后设备**立即**无法建立 MQTT 连接（认证回调校验 status，并同步清认证缓存，
     * 不必等缓存 TTL 过期）。
     *
     * <p><b>与删除的区别</b>：停用**保留全部数据**（遥测、日志、影子、指令），
     * 可一键恢复 —— 删除是清空且不可逆。设备返修、临时管控等场景应该用停用。
     */
    int updateStatus(List<String> deviceKeys, int status);

    /**
     * 分页查询设备操作日志（详情页「操作日志」tab）
     * @param deviceKey 设备标识
     * @param page 页码
     * @param size 每页数量
     * @return 日志分页
     */
    Page<Map<String, Object>> listDeviceLogs(String deviceKey, int page, int size);
    /**
     * 查询设备最新遥测数据
     * @param deviceKey 设备密钥
     * @return 最新遥测数据
     */
    Map<String, Object> getLatestTelemetry(String deviceKey);
    /**
     * 查询设备遥测历史数据
     * @param deviceKey 设备密钥
     * @param property 属性名
     * @param start 开始时间
     * @param end 结束时间
     * @return 遥测历史数据
     */
    List<TelemetryPointVO> getTelemetryHistory(String deviceKey, String property, Long start, Long end);
    /**
     * 查询设备TSL
     * @param deviceKey 设备密钥
     * @return TSL
     */
    Map<String, Object> getTsl(String deviceKey);
    /**
     * 注册设备
     * @param request 注册请求
     * @return 注册结果
     */
    DeviceRegisterVO register(DeviceRegisterRequest request);
    /**
     * 批量注册设备
     * @param request 注册请求
     * @return 注册结果列表
     */
    List<DeviceRegisterVO> registerBatch(DeviceRegisterRequest request);
}