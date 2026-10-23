# Privacy Policy — KeyFlow

**Last updated:** October 23, 2026  
**Effective date:** October 23, 2026  
**Permanent URL:** `https://keyflow.arateki.com/privacy`

---

**Arateki LTDA** (registration/tax ID CNPJ 45.642.122/0001-10, based in Fortaleza/CE, Brazil) developed **KeyFlow** with one fundamental commitment: **your privacy comes first**. KeyFlow is an Android keyboard engineered for high productivity, responsive typing, and on-demand voice dictation.

This policy clearly explains, in plain language and in compliance with GDPR and LGPD regulations, what data is processed, how it is safeguarded, and what your rights are.

---

## 1. Core Principle: What We DO NOT Collect

To provide complete peace of mind when using KeyFlow as your default Android keyboard:

- **We DO NOT collect, record, or transmit what you type:** Keystrokes, passwords, private messages, documents, and search terms are processed strictly in your device's local memory and delivered directly to the active application via `InputConnection`. We never log or transmit typed characters over the internet.
- **We DO NOT have continuous microphone access:** Your microphone is activated only when you deliberately tap the voice dictation button (on the keyboard or floating orb) and shuts down immediately when dictation ends.
- **We DO NOT collect sensitive personal data:** The app does not request or use permissions for precise or coarse GPS location (`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`), address book contacts, personal photos, or browsing history.

---

## 2. What Data Is Processed and Why

### a) Voice Audio Recordings for Dictation
- **What it is:** Audio captured while dictation is actively recording.
- **Purpose:** Transcribing your spoken words into written text upon your explicit request.
- **How it works:**
  - **Local (Offline) Mode:** Audio is processed entirely inside your device using compact on-device models (`sherpa-onnx`). **Zero audio bytes ever leave your phone.**
  - **Cloud Mode (OpenAI, Groq, or Custom Endpoint):** Audio is sent directly from your device over an encrypted HTTPS connection to your chosen API endpoint, using your personal API key (BYOK).
- **On-Device Storage and Deletion:** Audio files are kept in a temporary cache (`cacheDir/keyflow-recordings/`) during transcription and during a 3-second cancellation window (allowing you to undo sending). The file is **deleted immediately** once transcription finishes (whether successful or failed) or after the cancellation window expires. Stale recordings older than 24 hours are automatically removed during app startup.
- **Third-Party Provider Retention:** For cloud providers (OpenAI, Groq, or custom endpoints), retention and processing are governed strictly by each provider's privacy policy and your account terms. We cannot guarantee zero retention on third-party servers. If you require absolute assurance that audio never leaves your device, select 100% offline local transcription.

### b) Text Prompts for AI Reasoning Models
- **What it is:** Text you enter when testing AI models in settings.
- **Purpose:** Generating AI responses or customizing keyboard themes.
- **How it works:**
  - **Local Models (LiteRT-LM):** Processed 100% offline on your device.
  - **Cloud Providers (OpenAI, Anthropic, Google):** Sent directly from your device to the selected provider's API using your personal API key or connected subscription (OAuth). On OpenAI, requests specify `store: false` to request that prompts are not stored in completion history, though platform retention (e.g. up to 30 days for abuse monitoring under OpenAI API terms) remains subject to their policy. Anthropic and Google retain data according to their respective policies. When using Anthropic with an API key for theme customization, theme metadata and generated SVG previews may be transmitted as tool parameters.
- **KeyFlow Agent:** Interactive assistant feature planned as "Coming soon" for future v1 updates.

### c) API Keys and Authentication Credentials
- **What it is:** Your personal API keys and OAuth access tokens.
- **How they are handled:** Stored on your device and encrypted by the **Android Keystore** (AES-256-GCM). They are **never** sent to Arateki servers and are excluded from Android cloud backups. During requested operations, credentials or tokens are transmitted directly and securely (HTTPS) to the respective third-party provider solely for authentication.

### d) Anonymous Device Identifier, Integrity, and Purchases
- **What it is:** A locally generated random identifier (UUIDv4), Google Play Integrity tokens, and purchase receipts (`purchaseToken`).
- **Purpose:** Verifying app authenticity, managing and validating entitlement status — including the one-time **R$ 15** purchase (lifetime theme unlock and permanent ad removal) — and restoring purchases across reinstalls.
- **How it is transmitted:** After completing onboarding, the app registers the anonymous UUID and integrity token with the Arateki API (`keyflow-api.arateki.com/v1/register`, `v1/account`). Upon purchase or restore, receipts are validated at `/v1/purchases/redeem`. A cryptographic hash of the UUID is also sent to Google Play Billing (`obfuscatedAccountId`). Purchasing does not stop periodic entitlement checks.
- **Privacy:** This identifier is completely anonymous and never linked to your name, email, or phone number.

### e) Advertisements and Consent (Google AdMob / UMP)
- KeyFlow displays standard banner and interstitial ads **strictly within the settings app (`SetupActivity`)**.
- **No ads are ever displayed on the keyboard or floating orb.**
- We use the **Google User Messaging Platform (UMP)** to request consent for ads in compliance with GDPR and LGPD.
- **Review at any time:** Users can review or revoke advertising consent at any time via the **"Ads privacy"** option located in the **Docs** tab in settings.
- The one-time purchase of **R$ 15** permanently disables all advertisements.

---

## 3. Third Parties and Data Sharing

Arateki **does not sell, rent, or trade** personal data. Data is shared exclusively to deliver the functionality you explicitly choose:

1. **AI Providers Chosen by You:**
   - **OpenAI:** [OpenAI Privacy Policy](https://openai.com/policies/privacy-policy/)
   - **Groq:** [Groq Privacy Policy](https://groq.com/privacy-policy/)
   - **Anthropic:** [Anthropic Privacy Policy](https://www.anthropic.com/privacy)
   - **Google Generative AI:** [Google Privacy Policy](https://policies.google.com/privacy)
2. **Infrastructure, Payments, and Ad Platforms:**
   - **Google Play:** For purchase processing and app integrity verification ([Google Privacy](https://policies.google.com/privacy)).
   - **Google AdMob:** For serving ads in settings for free-tier users ([Google Ad Technologies](https://policies.google.com/technologies/ads)). The SDK may collect advertising IDs, IP addresses, and coarse location derived from IP.
   - **Hugging Face and Authorized Mirrors:** For downloading public model weights ([Hugging Face Privacy](https://huggingface.co/privacy)), or alternative mirror URLs specified by the remote catalog.
   - **Arateki API (`keyflow-api.arateki.com`):** Receives anonymous UUID, Play Integrity tokens, and purchase receipts for license validation, and serves remote configuration and model catalogs (`/v1/config`, `/v1/models`). [YAN: Server log retention policy — recommended: 30 days for technical error and integrity logs].
3. **Voluntary Email Support:**
   - When using the support email link in Docs, a draft email is created with basic device diagnostics (model, Android version, app version). Sending is entirely manual and voluntary via the user's email client.

---

## 4. What Remains 100% On-Device

- All typed characters and keystrokes on the Canvas keyboard (zero telemetry and zero logging).
- On-device spell checking and suggestions based on static built-in dictionaries (the app does not learn or record user typing habits).
- Local voice dictation processing (sherpa-onnx).
- Local text reasoning processing (LiteRT-LM).
- Local storage of API keys and login tokens, protected by Android Keystore.
- Imported custom theme packages (.zip), parsed and stored locally.

---

## 5. Data Retention and Deletion

- **Voice audio:** Deleted from local storage immediately upon transcription completion or error, or upon expiration of the 3-second cancellation window. Automatic cleanup removes stale files older than 24 hours.
- **On your device:** You can delete local configuration, API keys, and models by clearing app data in Android Settings or by uninstalling KeyFlow.
- **Identity Restoration (Google Block Store):** To prevent losing legitimate purchases upon app reinstallation or device migration, the anonymous device UUID may be restored via Google Block Store.
- **On Arateki API:** Since we maintain no user accounts or email lists, records linked to the anonymous UUID exist solely to validate licenses. [YAN: Define procedure and timeframe for deleting records on the API upon request via contato@arateki.com].
- **On External Providers:** Requests to delete data processed by third-party cloud providers (OpenAI, Anthropic, Google) must be submitted directly through those respective providers.

---

## 6. Security

We implement rigorous technical safeguards:
- All external network communications enforce **TLS/HTTPS**.
- Locally stored credentials and tokens are encrypted and protected by the **Android Keystore** (AES-256-GCM).
- Heavy computational tasks are isolated in dedicated worker processes (`:inference` and `:reasoning`) to ensure memory safety.

---

## 7. User Rights (GDPR & LGPD)

In compliance with GDPR and LGPD regulations:
- You have the right to transparent information regarding data processing.
- You have the right to review and revoke advertising consent at any time via settings ("Ads privacy").
- You have the right to request deletion or clarification regarding processed data by contacting `contato@arateki.com`.

---

## 8. Children's Privacy

KeyFlow is not directed at children under the age of 13 and does not knowingly collect personal information from children. If we become aware that data from a child has been collected inadvertently, it will be promptly deleted.

---

## 9. Contact Us

If you have questions, comments, or requests regarding this Privacy Policy or our data protection practices, please contact:

- **Email:** `contato@arateki.com`
- **Data Controller:** Arateki LTDA (CNPJ 45.642.122/0001-10)  
  Fortaleza, Ceará, Brazil
