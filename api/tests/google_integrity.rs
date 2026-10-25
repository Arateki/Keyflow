use keyflow_api::google::auth::ServiceAccountKey;
use keyflow_api::google::integrity::IntegrityClient;
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

fn test_key(token_uri: String) -> ServiceAccountKey {
    ServiceAccountKey {
        client_email: "test@keyflow-test.iam.gserviceaccount.com".to_string(),
        private_key: include_str!("fixtures/test_service_account_key.pem").to_string(),
        token_uri,
    }
}

#[tokio::test]
async fn decode_returns_parsed_payload_on_success() {
    let google = MockServer::start().await;

    Mock::given(method("POST"))
        .and(path("/token"))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "access_token": "fake-access-token",
            "token_type": "Bearer",
            "expires_in": 3600
        })))
        .mount(&google)
        .await;

    Mock::given(method("POST"))
        .and(path("/v1/com.arateki.keyflow:decodeIntegrityToken"))
        .and(header("authorization", "Bearer fake-access-token"))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "tokenPayloadExternal": {
                "requestDetails": { "requestPackageName": "com.arateki.keyflow" },
                "appIntegrity": { "appRecognitionVerdict": "PLAY_RECOGNIZED" },
                "deviceIntegrity": { "deviceRecognitionVerdict": ["MEETS_DEVICE_INTEGRITY"] }
            }
        })))
        .mount(&google)
        .await;

    let client = IntegrityClient::new(
        reqwest::Client::new(),
        google.uri(),
        "com.arateki.keyflow".to_string(),
        test_key(format!("{}/token", google.uri())),
    );

    let payload = client.decode("fake-integrity-token").await.unwrap();

    assert_eq!(
        payload.request_details.request_package_name,
        "com.arateki.keyflow"
    );
    assert_eq!(
        payload.app_integrity.app_recognition_verdict,
        "PLAY_RECOGNIZED"
    );
    assert_eq!(
        payload.device_integrity.device_recognition_verdict,
        vec!["MEETS_DEVICE_INTEGRITY".to_string()]
    );
}

#[tokio::test]
async fn decode_propagates_error_status_from_google() {
    let google = MockServer::start().await;

    Mock::given(method("POST"))
        .and(path("/token"))
        .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "access_token": "fake-access-token",
            "token_type": "Bearer",
            "expires_in": 3600
        })))
        .mount(&google)
        .await;

    Mock::given(method("POST"))
        .and(path("/v1/com.arateki.keyflow:decodeIntegrityToken"))
        .respond_with(ResponseTemplate::new(400))
        .mount(&google)
        .await;

    let client = IntegrityClient::new(
        reqwest::Client::new(),
        google.uri(),
        "com.arateki.keyflow".to_string(),
        test_key(format!("{}/token", google.uri())),
    );

    let err = client.decode("bad-token").await.unwrap_err();

    assert!(matches!(
        err,
        keyflow_api::google::integrity::IntegrityError::RequestFailed(400)
    ));
}
