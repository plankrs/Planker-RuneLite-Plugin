package com.planker;

import javax.inject.Inject;
import okhttp3.HttpUrl;
import okhttp3.Request;

public class AuthenticatedRequestFactory
{
    private final PlankerConfig config;
    @Inject public AuthenticatedRequestFactory(PlankerConfig config){this.config=config;}
    public Request.Builder create(String relativePath)
    {
        String baseUrlValue=config.apiUrl()==null?"":config.apiUrl().trim();
        String apiKey=config.apiKey()==null?"":config.apiKey().trim();
        if(baseUrlValue.isEmpty()||apiKey.isEmpty())throw new IllegalArgumentException("API URL or plugin key is missing.");
        HttpUrl baseUrl=HttpUrl.parse(baseUrlValue);
        if(baseUrl==null||!"https".equalsIgnoreCase(baseUrl.scheme()))throw new IllegalArgumentException("The PlankRS API URL must be a valid HTTPS address.");
        return new Request.Builder().url(baseUrl.newBuilder().addPathSegments(relativePath).build()).header("Authorization","Bearer "+apiKey).header("User-Agent",PluginVersion.USER_AGENT);
    }
}
