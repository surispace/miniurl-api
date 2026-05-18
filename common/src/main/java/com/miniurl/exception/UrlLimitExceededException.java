package com.miniurl.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class UrlLimitExceededException extends RuntimeException {

    private final String limitType;
    private final int limit;
    private final int currentCount;
    private final String retryMessage;
    private final LocalDateTime retryAfter;
    private final String uiMessage;

    public UrlLimitExceededException(String limitType, int limit, int currentCount) {
        super(formatMessage(limitType, limit, currentCount));
        this.limitType = limitType;
        this.limit = limit;
        this.currentCount = currentCount;
        
        // Calculate retry time and message based on limit type
        switch (limitType) {
            case "per minute":
                this.retryAfter = LocalDateTime.now().plusSeconds(60);
                this.retryMessage = String.format("Please try again after %s", 
                    this.retryAfter.format(DateTimeFormatter.ofPattern("h:mm:ss a")));
                this.uiMessage = "You created 10 in last minute, please wait another minute";
                break;
            case "per day":
                this.retryAfter = LocalDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0);
                this.retryMessage = String.format("Please try again tomorrow after midnight");
                this.uiMessage = "Your daily limit quota reached, please try tomorrow";
                break;
            case "per month":
                LocalDateTime nextMonth = LocalDateTime.now().plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
                String nextMonthName = nextMonth.format(DateTimeFormatter.ofPattern("MMMM"));
                this.retryAfter = nextMonth;
                this.retryMessage = String.format("Please try again on 1st of %s", nextMonthName);
                this.uiMessage = "Your monthly limit quota reached, please try next month";
                break;
            default:
                this.retryAfter = LocalDateTime.now().plusMinutes(1);
                this.retryMessage = "Please try again later";
                this.uiMessage = "Limit reached, please try again later";
        }
    }

    private static String formatMessage(String limitType, int limit, int currentCount) {
        String message = switch (limitType) {
            case "per minute" -> String.format("You have reached the URL creation limit of %d URLs per minute (current: %d).", limit, currentCount);
            case "per day" -> String.format("You have reached the URL creation limit of %d URLs per day (current: %d).", limit, currentCount);
            case "per month" -> String.format("You have reached the URL creation limit of %d URLs per month (current: %d).", limit, currentCount);
            default -> String.format("URL creation limit exceeded: %s (limit: %d, current: %d)", limitType, limit, currentCount);
        };
        return message;
    }

    public String getLimitType() {
        return limitType;
    }

    public int getLimit() {
        return limit;
    }

    public int getCurrentCount() {
        return currentCount;
    }

    public String getRetryMessage() {
        return retryMessage;
    }

    public LocalDateTime getRetryAfter() {
        return retryAfter;
    }

    public String getUiMessage() {
        return uiMessage;
    }

    @Override
    public String getMessage() {
        return super.getMessage() + " " + retryMessage;
    }
}
