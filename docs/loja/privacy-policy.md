# Privacy Policy — KeyFlow

**Last updated:** October 23, 2026  
**Effective date:** October 23, 2026  
**Permanent URL:** [YAN: Permanent hosting URL, e.g., `https://arateki.com/keyflow/privacy`]

---

**Arateki** [YAN: Full legal company name, registration number/tax ID if applicable] created **KeyFlow** with one core commitment: **your privacy comes first**. KeyFlow is an Android keyboard designed for typing productivity and on-demand voice dictation.

This policy clearly explains what data is processed, how it is safeguarded, and what your rights are.

---

## 1. Core Principle: What We DO NOT Collect

To give you complete peace of mind when using KeyFlow as your default Android keyboard:

- **We DO NOT collect, record, or transmit what you type:** Keystrokes, passwords, private messages, documents, and search terms are processed strictly in your device's local memory and delivered directly to the app in which you are typing.
- **We DO NOT have continuous microphone access:** Your microphone is activated only when you deliberately tap the voice dictation button (on the keyboard or floating orb) and shuts down immediately when dictation ends.
- **We DO NOT collect sensitive personal data:** We never access or collect your GPS location, contacts, browsing history, or camera/photos.

---

## 2. What Data Is Processed and Why

### a) Voice Audio Recordings for Dictation
- **What it is:** Audio captured while dictation is actively recording.
- **Purpose:** Transcribing your spoken words into written text.
- **How it works:**
  - **Local (Offline) Mode:** Audio is processed entirely inside your device using compact on-device models (`sherpa-onnx`). **Zero audio bytes ever leave your phone.**
  - **Cloud Mode (OpenAI, Groq, or Custom Endpoint):** Audio is sent directly from your device over an encrypted HTTPS connection to your chosen API endpoint, using your own API key.
- **Retention:** Audio files are temporarily cached on your device (`cacheDir/keyflow-recordings/`) and **deleted immediately** once transcription finishes or if you cancel the recording.

### b) Text Prompts for AI Reasoning Models
- **What it is:** Text you enter when testing reasoning models or interacting with the KeyFlow Agent in settings.
- **Purpose:** Generating AI responses or modifying keyboard theme styles upon your request.
- **How it works:**
  - **Local Models (LiteRT-LM):** Processed 100% offline on your device.
  - **Cloud Providers (OpenAI, Anthropic, Google):** Sent directly from your device to the selected provider's API using your personal API key or connected subscription (OAuth). On OpenAI, requests explicitly specify `store: false`.

### c) API Keys and Authentication Credentials
- **What it is:** Your personal API keys and OAuth access tokens.
- **How they are stored:** Saved **only on your device**, encrypted by the Android Keystore (AES-256-GCM). They are **never** transmitted to Arateki servers and are excluded from Android cloud backups.

### d) Anonymous App Identifier and Purchases
- **What it is:** A locally generated random identifier (UUIDv4) and Google Play purchase receipts (`purchaseToken`).
- **Purpose:** Validating the one-time **R$ 15** purchase (lifetime skin unlock and permanent ad removal) and maintaining entitlement state across reinstalls.
- **Privacy:** This identifier is entirely anonymous and never associated with your real name, email address, or phone number.

### e) Advertisements and Consent (Google AdMob / UMP)
- KeyFlow displays standard banner and interstitial ads **strictly within the settings app (`SetupActivity`)**.
- **No ads are ever displayed on the keyboard or the floating orb.**
- We use the **Google User Messaging Platform (UMP)** to request consent for personalized ads in compliance with GDPR and LGPD.
- The one-time purchase of **R$ 15** permanently disables all advertisements.

---

## 3. Third Parties and Data Sharing

Arateki **does not sell, rent, or trade** personal data. Data is shared exclusively to provide the services you explicitly choose to enable:

1. **AI Providers Chosen by You:**
   - **OpenAI:** [OpenAI Privacy Policy](https://openai.com/policies/privacy-policy/)
   - **Groq:** [Groq Privacy Policy](https://groq.com/privacy-policy/)
   - **Anthropic:** [Anthropic Privacy Policy](https://www.anthropic.com/privacy)
   - **Google Generative AI:** [Google Privacy Policy](https://policies.google.com/privacy)
2. **Infrastructure and Payment Platforms:**
   - **Google Play:** For in-app purchase processing and app integrity verification ([Google Privacy](https://policies.google.com/privacy)).
   - **Google AdMob:** For serving ads in the settings app for free-tier users ([Google Ad Technologies](https://policies.google.com/technologies/ads)).
   - **Hugging Face:** For downloading public model weights for local voice and reasoning models ([Hugging Face Privacy](https://huggingface.co/privacy)).
   - **Arateki API (`keyflow-api.arateki.com`):** Receives only Google Play purchase validation tokens and the anonymous device UUID to record unlock status. [YAN: Server log retention policy — recommended: 30 days for technical error logs].

---

## 4. What Remains 100% On-Device

- All typed characters and keystrokes on the Canvas keyboard.
- Local voice dictation processing (sherpa-onnx).
- Local text reasoning processing (LiteRT-LM).
- All personal API keys and OAuth tokens (encrypted in Android Keystore).
- Personal dictionary and custom word suggestions.

---

## 5. Data Retention and Deletion

- **Voice audio:** Deleted from local storage immediately upon transcription completion or recording cancellation.
- **On your device:** You can delete all keys, downloaded models, and settings at any time by clearing application data in Android Settings or uninstalling KeyFlow.
- **On Arateki API:** Because no personally identifiable accounts exist, resetting or uninstalling the app disassociates the device from the anonymous UUID.

---

## 6. Security

We implement rigorous technical safeguards:
- All external network communications enforce **TLS/HTTPS**.
- Locally stored credentials are encrypted using hardware-backed cryptographic keys (**Android Keystore**).
- Heavy computational tasks are isolated in dedicated worker processes (`:inference` and `:reasoning`) to ensure memory safety.

---

## 7. User Rights (LGPD & GDPR)

Under LGPD and GDPR, you have the right to transparent information regarding data processing, the right to revoke advertising consent at any time via settings, and the right to request deletion of data.

---

## 8. Children's Privacy

KeyFlow is not directed at children under the age of 13 and does not knowingly collect personal information from children. If we become aware that data from a child has been collected inadvertently, it will be promptly deleted.

---

## 9. Contact Us

If you have questions, comments, or requests regarding this Privacy Policy, please contact our privacy team:

- **Email:** `contato@arateki.com`
- **Developer:** Arateki [YAN: Physical business address if required by Play Console developer profile]
