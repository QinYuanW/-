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
     * @return 设备列表
     */
    Page<DeviceVO> listDevices(int page, int size, String keyword, String productKey, Integer onlineStatus);

    /**
     * 批量删除设备（级联清理遥测/日志/指令/影子，3.3 列表增强）
     * @param deviceKeys 待删除设备标识列表
     * @return 实际删除数量
     */
    int deleteDevices(List<String> deviceKeys);

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