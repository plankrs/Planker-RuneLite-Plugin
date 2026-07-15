package com.planker.model;

import lombok.Data;

@Data
public class InstallationCheckInResponse
{
    private String registeredRsn;
    private int policyVersion;
    private String latestPluginVersion;
    private boolean updateAvailable;
    private boolean updateRequired;
}
