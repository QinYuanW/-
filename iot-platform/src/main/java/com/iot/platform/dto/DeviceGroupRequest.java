package com.iot.platform.dto;

import lombok.Data;

/** E4 分组新增 / 编辑请求。 */
@Data
public class DeviceGroupRequest {

    private String name;

    private String description;

    private Integer sortOrder;
}
