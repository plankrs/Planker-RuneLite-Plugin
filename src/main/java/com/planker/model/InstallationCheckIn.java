package com.planker.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InstallationCheckIn
{
    private String pluginVersion;
    private Integer policyVersion;
    private int queuedEventCount;
    private String rsn;
    private String lastError;
}
