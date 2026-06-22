package team.cfpa.touhoustepfun.compat.ai.mimo;

import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;

public final class MimoEndpointResolver {
    private static final String PAYG_HOST = "api.xiaomimimo.com";
    private static final String TOKEN_PLAN_CN_HOST = "token-plan-cn.xiaomimimo.com";

    private MimoEndpointResolver() {
    }

    public static URI resolve(String configuredUrl, String secretKey) {
        URI uri = URI.create(configuredUrl);
        if (!isTokenPlanKey(secretKey) || !PAYG_HOST.equalsIgnoreCase(uri.getHost())) {
            return uri;
        }
        try {
            return new URI(uri.getScheme(), uri.getUserInfo(), TOKEN_PLAN_CN_HOST, uri.getPort(),
                    uri.getPath(), uri.getQuery(), uri.getFragment());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid MiMo URL: " + configuredUrl, e);
        }
    }

    private static boolean isTokenPlanKey(String secretKey) {
        return StringUtils.startsWith(secretKey, "tp-");
    }
}
