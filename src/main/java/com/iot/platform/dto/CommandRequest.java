package com.iot.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Map;

@Data
public class CommandRequest {

    @JsonProperty("command_type")
    private Integer commandType;

    private Map<String, Object> payload;

    /**
     * E1 语义分家（2026-09-12 决策）：设备离线时的行为。
     * <ul>
     *   <li>{@code false}（缺省）—— <b>命令语义</b>：瞬时动作，离线直接失败（4005 设备不在线），
     *       过期的上下文不应被延迟执行；</li>
     *   <li>{@code true} —— <b>期望状态语义</b>：离线挂起（status=0），设备上线由
     *       {@code replayPending} 自动补发。仅「设备影子-期望值」路径使用。</li>
     * </ul>
     */
    @JsonProperty("queue_if_offline")
    private Boolean queueIfOffline;
}
