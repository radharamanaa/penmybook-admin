Feature: Admin step-up OTP issues a token that unlocks gated money actions (F2)
  POST /v1/admin/otp/start mints an OTP code for the signed-in staff admin (via the entitlements
  OTP store, so the dev plaintext code lands in otp_challenges.dev_plaintext_code for local
  retrieval). POST /v1/admin/otp/verify checks the code and, on success, mints an admin-api
  step-up token bound over the action + params. That token unlocks the step-up-gated grant and
  refund writes; a wrong code mints no token, and the gated writes still reject without one.

  Scenario: Verifying the right code mints a step-up token bound to the grant amount
    Given a platform admin is signed in for step-up
    When they start an OTP challenge for action "grant_credit" granting 500 cents
    And they verify the OTP challenge with the correct code
    Then a step-up token is returned
    And the token is bound to action "grant_credit" for 500 cents

  Scenario: Verifying a wrong code mints no token
    Given a platform admin is signed in for step-up
    When they start an OTP challenge for action "grant_credit" granting 500 cents
    And they verify the OTP challenge with an incorrect code
    Then no step-up token is returned
    And the verify failure reason is reported

  Scenario: The minted token unlocks a wallet-money grant
    Given a platform admin is signed in for step-up
    When they start an OTP challenge for action "grant_credit" granting 500 cents
    And they verify the OTP challenge with the correct code
    And they grant 500 cents using the minted step-up token
    Then the grant response status is 200
    And the wallet was credited via entitlements

  Scenario: The minted token unlocks a refund
    Given a platform admin is signed in for step-up
    When they start an OTP challenge for action "refund" refunding payment 11111111-1111-1111-1111-111111111111 of 2500 cents
    And they verify the OTP challenge with the correct code
    And they refund payment 11111111-1111-1111-1111-111111111111 of 2500 cents using the minted step-up token
    Then the grant response status is 200
    And the refund was sent to billing with an idempotency key
    And the refund receipt reports requested and refunded amounts

  Scenario: A refund naming an approved request forwards refundRequestId to billing
    Given a platform admin is signed in for step-up
    When they start an OTP challenge for action "refund" refunding payment 11111111-1111-1111-1111-111111111111 of 2500 cents against request 22222222-2222-2222-2222-222222222222
    And they verify the OTP challenge with the correct code
    And they refund payment 11111111-1111-1111-1111-111111111111 of 2500 cents naming request 22222222-2222-2222-2222-222222222222 using the minted step-up token
    Then the grant response status is 200
    And the refund was sent to billing naming request 22222222-2222-2222-2222-222222222222
