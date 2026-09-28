-- Seeds de dev do design §Manifest & config remota (MDL-01, CFG-01). Edição no MVP é via
-- SQL/migration (painel admin fora de escopo) — ver design.md.

insert into documents (key, body) values (
    'config',
    '{
        "products": {
            "credits_10": {"minutes": 10, "grantsEntitlements": false},
            "credits_20": {"minutes": 25, "grantsEntitlements": true},
            "credits_50": {"minutes": 70, "grantsEntitlements": true},
            "unlock_forever": {"grantsEntitlements": true}
        },
        "ads": {
            "note": "placeholder — Yan calibra sem release (M4)",
            "initialCooldownSeconds": 60,
            "cooldownFactor": 2,
            "maxCooldownSeconds": 3600,
            "minEngagementSeconds": 5,
            "postPurchaseWindowSeconds": 300,
            "dailyCap": 10
        },
        "transcription": {
            "maxAudioBytes": 26214400,
            "maxDurationSeconds": 300,
            "timeoutMs": 30000,
            "providers": []
        },
        "minAppVersion": "1.0.0",
        "killSwitch": false
    }'::jsonb
);

insert into documents (key, body) values (
    'models',
    '{
        "note": "placeholder — native-voice-models ainda não colheu os modelos reais",
        "models": []
    }'::jsonb
);
