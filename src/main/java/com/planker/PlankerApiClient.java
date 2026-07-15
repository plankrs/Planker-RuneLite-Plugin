package com.planker;

import com.google.gson.Gson;
import com.planker.model.InstallationCheckIn;
import com.planker.model.InstallationCheckInResponse;
import com.planker.model.NotificationPolicy;
import com.planker.model.PlankerEvent;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
public class PlankerApiClient
{
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final AuthenticatedRequestFactory requestFactory;
    private final ApiResponseInterpreter responseInterpreter;

    @Inject
    public PlankerApiClient(OkHttpClient httpClient, Gson gson, AuthenticatedRequestFactory requestFactory, ApiResponseInterpreter responseInterpreter)
    {
        this.httpClient = httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();
        this.gson = gson;
        this.requestFactory = requestFactory;
        this.responseInterpreter = responseInterpreter;
    }

    public void send(PlankerEvent gameEvent, ResultCallback callback)
    {
        Request eventRequest;
        try
        {
            eventRequest = requestFactory.create("api/v1/events")
                .post(RequestBody.create(JSON_MEDIA_TYPE, gson.toJson(gameEvent)))
                .build();
        }
        catch (IllegalArgumentException configurationError)
        {
            callback.onFailure(new ApiFailure(
                0, configurationError.getMessage(), true, false, 0L));
            return;
        }

        httpClient.newCall(eventRequest).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call requestCall, IOException networkFailure)
            {
                log.debug("Event submission failed for event {}: {}",
                    gameEvent == null ? "unknown" : gameEvent.getEventId(), responseInterpreter.safeMessage(networkFailure));
                log.trace("Event submission failure", networkFailure);
                callback.onFailure(new ApiFailure(
                    0, responseInterpreter.safeMessage(networkFailure), false, true, 0L));
            }

            @Override
            public void onResponse(Call requestCall, Response httpResponse) throws IOException
            {
                try (Response closeableResponse = httpResponse)
                {
                    String responseBody = readResponseBody(closeableResponse);
                    if (!closeableResponse.isSuccessful())
                    {
                        callback.onFailure(responseInterpreter.toFailure(
                            closeableResponse.code(), closeableResponse.header("Retry-After")));
                        return;
                    }
                    callback.onSuccess();
                }
                catch (RuntimeException callbackFailure)
                {
                    log.error("Unexpected event response handling failure: {}", callbackFailure.getMessage());
                    log.debug("Event response handling failure", callbackFailure);
                    callback.onFailure(new ApiFailure(
                        0, "Unexpected response handling failure.", false, true, 0L));
                }
            }
        });
    }

    public void checkIn(InstallationCheckIn checkInPayload, CheckInCallback callback)
    {
        Request checkInRequest;
        try
        {
            checkInRequest = requestFactory.create("api/v1/installations/check-in")
                .post(RequestBody.create(JSON_MEDIA_TYPE, gson.toJson(checkInPayload)))
                .build();
        }
        catch (IllegalArgumentException configurationError)
        {
            callback.onFailure(configurationError.getMessage());
            return;
        }

        httpClient.newCall(checkInRequest).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call requestCall, IOException networkFailure)
            {
                log.debug("Plugin check-in request failed: {}", responseInterpreter.safeMessage(networkFailure));
                log.trace("Plugin check-in failure", networkFailure);
                callback.onFailure(responseInterpreter.friendlyConnectionError(0, responseInterpreter.safeMessage(networkFailure)));
            }

            @Override
            public void onResponse(Call requestCall, Response httpResponse) throws IOException
            {
                try (Response closeableResponse = httpResponse)
                {
                    String responseBody = readResponseBody(closeableResponse);
                    if (!closeableResponse.isSuccessful())
                    {
                        callback.onFailure(responseInterpreter.friendlyConnectionError(closeableResponse.code(), responseBody));
                        return;
                    }

                    try
                    {
                        InstallationCheckInResponse checkInResponse =
                            gson.fromJson(responseBody, InstallationCheckInResponse.class);
                        if (checkInResponse == null)
                        {
                            throw new IllegalStateException("Check-in response was empty");
                        }
                        callback.onSuccess(checkInResponse);
                    }
                    catch (RuntimeException invalidPayload)
                    {
                        log.warn("Plugin check-in returned an invalid response payload");
                        log.debug("Invalid check-in response payload", invalidPayload);
                        callback.onFailure("The server returned an invalid connection response.");
                    }
                }
            }
        });
    }

    public void fetchPolicy(PolicyCallback callback)
    {
        Request policyRequest;
        try
        {
            policyRequest = requestFactory.create("api/v1/policy").get().build();
        }
        catch (IllegalArgumentException configurationError)
        {
            callback.onFailure(configurationError.getMessage());
            return;
        }

        httpClient.newCall(policyRequest).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call requestCall, IOException networkFailure)
            {
                log.debug("Policy request failed: {}", responseInterpreter.safeMessage(networkFailure));
                log.trace("Policy request failure", networkFailure);
                callback.onFailure(responseInterpreter.safeMessage(networkFailure));
            }

            @Override
            public void onResponse(Call requestCall, Response httpResponse) throws IOException
            {
                try (Response closeableResponse = httpResponse)
                {
                    String responseBody = readResponseBody(closeableResponse);
                    if (!closeableResponse.isSuccessful())
                    {
                        callback.onFailure("HTTP " + closeableResponse.code());
                        return;
                    }

                    try
                    {
                        NotificationPolicy notificationPolicy =
                            gson.fromJson(responseBody, NotificationPolicy.class);
                        if (notificationPolicy == null)
                        {
                            throw new IllegalStateException("Policy response was empty");
                        }
                        callback.onSuccess(notificationPolicy);
                    }
                    catch (RuntimeException invalidPayload)
                    {
                        log.warn("Policy endpoint returned an invalid response payload");
                        log.debug("Invalid policy response payload", invalidPayload);
                        callback.onFailure("The server returned an invalid policy response.");
                    }
                }
            }
        });
    }

    private String readResponseBody(Response httpResponse) throws IOException
    {
        if (httpResponse.body() == null)
        {
            return "";
        }

        return httpResponse.body().string();
    }

    public interface ResultCallback
    {
        void onSuccess();
        void onFailure(ApiFailure apiFailure);
    }

    @Value
    public static class ApiFailure
    {
        int statusCode;
        String message;
        boolean authenticationFailure;
        boolean temporaryFailure;
        long retryAfterMillis;

        public boolean isPermanentFailure()
        {
            return !authenticationFailure && !temporaryFailure;
        }
    }

    public interface CheckInCallback
    {
        void onSuccess(InstallationCheckInResponse checkInResponse);
        void onFailure(String failureMessage);
    }

    public interface PolicyCallback
    {
        void onSuccess(NotificationPolicy notificationPolicy);
        void onFailure(String failureMessage);
    }
}
