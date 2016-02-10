/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.ratelimit;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.ratelimit.configuration.RateLimitConfiguration;
import io.gravitee.policy.ratelimit.configuration.RateLimitPolicyConfiguration;
import io.gravitee.policy.ratelimit.utils.DateUtils;
import io.gravitee.repository.ratelimit.api.RateLimitRepository;
import io.gravitee.repository.ratelimit.model.RateLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The rate limit policy, also known as throttling insure that a user (given its api key or IP address) is allowed
 * to make x requests per y time period.
 *
 * Useful when you want to ensure that your APIs does not get flooded with requests.
 *
 * @author David BRASSELY (brasseld at gmail.com)
 */
@SuppressWarnings("unused")
public class RateLimitPolicy {

    /**
     * LOGGER
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitPolicy.class);

    /**
     * The maximum number of requests that the consumer is permitted to make per time unit.
     */
    public static final String X_RATE_LIMIT_LIMIT = "X-Rate-Limit-Limit";

    /**
     * The number of requests remaining in the current rate limit window.
     */
    public static final String X_RATE_LIMIT_REMAINING = "X-Rate-Limit-Remaining";

    /**
     * The time at which the current rate limit window resets in UTC epoch seconds.
     */
    public static final String X_RATE_LIMIT_RESET = "X-Rate-Limit-Reset";

    private static char KEY_SEPARATOR = ':';

    /**
     * Rate limit policy configuration
     */
    private final RateLimitPolicyConfiguration rateLimitPolicyConfiguration;

    public RateLimitPolicy(RateLimitPolicyConfiguration rateLimitPolicyConfiguration) {
        this.rateLimitPolicyConfiguration = rateLimitPolicyConfiguration;
    }

    @OnRequest
    public void onRequest(Request request, Response response, ExecutionContext executionContext, PolicyChain policyChain) {
        RateLimitRepository<String> rateLimitRepository = executionContext.getComponent(RateLimitRepository.class);

        if (rateLimitRepository == null) {
            policyChain.failWith(new PolicyResult() {
                @Override
                public boolean isFailure() {
                    return true;
                }

                @Override
                public int httpStatusCode() {
                    return 500;
                }

                @Override
                public String message() {
                    return "No rate-limit repository has been configured.";
                }
            });

            return;
        }

        // We prefer currentTimeMillis in place of nanoTime() because nanoTime is relatively
        // expensive call and depends on the underlying architecture.
        long now = System.currentTimeMillis();

        List<RateLimitConfiguration> rateLimitConfigurations = rateLimitPolicyConfiguration.getRateLimits();
        int idx = 0;

        for(RateLimitConfiguration rateLimitConfiguration : rateLimitConfigurations) {
            String rateLimitKey = createRateLimitKey(request, executionContext, idx);
            RateLimit rateLimit = rateLimitRepository.get(new RateLimit(rateLimitKey));

            long endOfWindow = DateUtils.getEndOfWindow(
                    rateLimit.getLastRequest(),
                    rateLimitConfiguration.getPeriodTime(),
                    rateLimitConfiguration.getPeriodTimeUnit());

            boolean rateLimitExceeded = false;

            if (now >= endOfWindow) {
                rateLimit.setCounter(0);
            }

            if (rateLimit.getCounter() >= rateLimitConfiguration.getLimit()) {
                rateLimitExceeded = true;
            } else {
                // Update rate limiter
                // By default, weight is 1 (can be configurable in the future)
                rateLimit.setCounter(rateLimit.getCounter() + 1);
                rateLimit.setLastRequest(now);
            }

            // Set the time at which the current rate limit window resets in UTC epoch seconds.
            long resetTimeMillis = DateUtils.getEndOfPeriod(now,
                    rateLimitConfiguration.getPeriodTime(), rateLimitConfiguration.getPeriodTimeUnit());
            long resetTime = resetTimeMillis / 1000L;
            long remains = rateLimitConfiguration.getLimit() - rateLimit.getCounter();

            rateLimit.setResetTime(resetTimeMillis);
            rateLimitRepository.save(rateLimit);

            // Set Rate Limit headers on response
            if (rateLimitConfigurations.size() == 1 || rateLimitExceeded) {
                response.headers().set(X_RATE_LIMIT_LIMIT, Long.toString(rateLimitConfiguration.getLimit()));
                response.headers().set(X_RATE_LIMIT_REMAINING, Long.toString(remains));
                response.headers().set(X_RATE_LIMIT_RESET, Long.toString(resetTime));
            } else {
                response.headers().set(X_RATE_LIMIT_LIMIT + '-' + idx, Long.toString(rateLimitConfiguration.getLimit()));
                response.headers().set(X_RATE_LIMIT_REMAINING + '-' + idx, Long.toString(remains));
                response.headers().set(X_RATE_LIMIT_RESET + '-' + idx, Long.toString(resetTime));
            }

            if (rateLimitExceeded) {
                policyChain.failWith(createLimitExceeded(rateLimitConfiguration));
                return;
            }

            // Increment rate limiter index
            idx++;
        }

        policyChain.doNext(request, response);
    }

    private String createRateLimitKey(Request request, ExecutionContext executionContext, int idx) {
        // Rate limit key must contain :
        // 1_ API_ID
        // 2_ APP_ID
        // 3_ PATH
        // 4_ RATE_LIMIT_ID
        // 5_ GATEWAY_ID
        return (String) executionContext.getAttribute(ExecutionContext.ATTR_API) +
                KEY_SEPARATOR +
                executionContext.getAttribute(ExecutionContext.ATTR_APPLICATION) +
                KEY_SEPARATOR +
                executionContext.getAttribute(ExecutionContext.ATTR_RESOLVED_PATH).hashCode() +
                KEY_SEPARATOR + idx;
    }

    private PolicyResult createLimitExceeded(RateLimitConfiguration rateLimitConfiguration) {
        return new PolicyResult() {
            @Override
            public boolean isFailure() {
                return true;
            }

            @Override
            public int httpStatusCode() {
                return HttpStatusCode.TOO_MANY_REQUESTS_429;
            }

            @Override
            public String message() {
                return "Rate limit exceeded ! You reach the limit fixed to " + rateLimitConfiguration.getLimit() +
                        " requests per " + rateLimitConfiguration.getPeriodTime() + ' ' +
                        rateLimitConfiguration.getPeriodTimeUnit().name().toLowerCase();
            }
        };
    }
}
