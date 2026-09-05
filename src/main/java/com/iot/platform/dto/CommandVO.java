package com.iot.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.iot.platform.entity.Command;
import lombok.Data;

@Data
public class CommandVO {

    @JsonProperty("request_id")
    private String requestId;

    private Integer status;

    @JsonProperty("sent_at")
    private String sentAt;

    @JsonProperty("ack_at")
    private String ackAt;

    public static CommandVO from(Command c) {
        CommandVO vo = new CommandVO();
        vo.setRequestId(c.getRequestId());
        vo.setStatus(c.getStatus());
        vo.setSentAt(c.getSentAt() != null ? c.getSentAt().toString() : null);
        vo.setAckAt(c.getAckAt() != null ? c.getAckAt().toString() : null);
        return vo;
    }
}
