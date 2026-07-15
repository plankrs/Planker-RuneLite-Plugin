package com.planker;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ApiResponseInterpreter
{
    public String friendlyConnectionError(int statusCode,String detail){switch(statusCode){case 401:return "The plugin key is invalid or was replaced. Run /plugin-register again.";case 403:return "The logged-in RuneScape name does not match the account registered in Discord.";case 404:return "The PlankRS API URL is incorrect.";default:if(statusCode>=500)return "The PlankRS server encountered an error. Please try again later.";return detail==null||detail.trim().isEmpty()?"The PlankRS server is unreachable.":"The PlankRS server is unreachable: "+detail;}}
    public PlankerApiClient.ApiFailure toFailure(int statusCode,String retryAfter){boolean authentication=statusCode==401||statusCode==403;boolean temporary=statusCode==408||statusCode==425||statusCode==429||statusCode>=500;return new PlankerApiClient.ApiFailure(statusCode,"HTTP "+statusCode,authentication,temporary,parseRetryAfterMillis(retryAfter));}
    public String safeMessage(Exception exception){return exception==null||exception.getMessage()==null||exception.getMessage().trim().isEmpty()?"Network request failed.":exception.getMessage().trim();}
    private long parseRetryAfterMillis(String value){if(value==null||value.trim().isEmpty())return 0L;try{return Math.max(0L,Long.parseLong(value.trim())*1000L);}catch(NumberFormatException ignored){try{return Math.max(0L,ZonedDateTime.parse(value.trim(),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis());}catch(DateTimeParseException invalid){log.debug("Ignoring invalid Retry-After header");return 0L;}}}
}
