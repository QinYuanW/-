package com.iot.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.iot.platform.entity.Command;
import lombok.Data;

@Data
public class CommandVO {

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("command_type")
    private Integer commandType;

    /** 指令载荷（JSON 字符串） */
    private String payload;

    private Integer status;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("sent_at")
    private String sentAt;

    @JsonProperty("ack_at")
    private String ackAt;

    public static CommandVO from(Command c) {
        CommandVO vo = new CommandVO();
        vo.setRequestId(c.getRequestId());
        vo.setCommandType(c.getCommandType());
        vo.setPayload(c.getPayload());
        vo.setStatus(c.getStatus());
        vo.setCreatedAt(c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
        vo.setSentAt(c.getSentAt() != null ? c.getSentAt().toString() : null);
        vo.setAckAt(c.getAckAt() != null ? c.getAckAt().toString() : null);
        return vo;
    }
}
