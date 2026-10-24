package com.keyflow.admob;

import java.util.LinkedHashMap;
import java.util.Map;

/** Seleção usada pelo build e pelos testes JVM; nunca entra no APK. */
public final class AdMobIds {
    public static final String APP = "KEYFLOW_ADMOB_APP_ID";
    public static final String BANNER = "KEYFLOW_ADMOB_BANNER_ID";
    public static final String INTERSTITIAL = "KEYFLOW_ADMOB_INTERSTITIAL_ID";
    public static final String TEST_PUBLISHER = "3940256099942544";

    private AdMobIds() {}

    public static Map<String, String> select(boolean debug, Map<String, String> configured) {
        if (debug) return Map.of(
            APP, "ca-app-pub-3940256099942544~3347511713",
            BANNER, "ca-app-pub-3940256099942544/9214589741",
            INTERSTITIAL, "ca-app-pub-3940256099942544/1033173712"
        );
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : new String[] { APP, BANNER, INTERSTITIAL }) {
            String value = configured.getOrDefault(key, "").trim();
            String separator = key.equals(APP) ? "~" : "/";
            if (!value.matches("ca-app-pub-[0-9]{16}" + separator + "[0-9]{10}")
                    || value.contains(TEST_PUBLISHER)) {
                throw new IllegalArgumentException("AdMob release: " + key
                    + " ausente ou inválido. Configure no gradle.properties local ou no ambiente do CI"
                    + " um ID ca-app-pub- com 16 dígitos, " + separator
                    + " e 10 dígitos; o publisher de teste do Google é proibido.");
            }
            result.put(key, value);
        }
        return result;
    }
}
