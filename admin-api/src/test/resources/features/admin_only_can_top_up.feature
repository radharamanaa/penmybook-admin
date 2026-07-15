Feature: Wallet top-up endpoint is platform-admin only and step-up gated
  POST /v1/admin/users/{userId}/wallet/top-up MUST be gated by hasRole('PLATFORM_ADMIN');
  non-admin callers are rejected with HTTP 403 AND a decision=DENY audit row. A top-up mints
  spendable wallet MONEY exactly like a goodwill grant, so it is ALSO step-up (OTP) gated (H3) —
  an admin must present a valid X-Step-Up-Token bound over the exact amount. On success the wallet
  is credited AND a decision=ALLOW audit row is written.

  Scenario: A non-admin user is rejected with 403 and a DENY audit row is written
    Given a non-admin user is signed in
    When they attempt to top up a user wallet by 1500 cents
    Then the response status is 403
    And the audit log has a DENY row for action "WALLET_TOPUP"

  Scenario: A platform admin tops up a user wallet with a valid step-up token
    Given a platform admin is signed in
    When they top up a user wallet by 1500 cents
    Then the response status is 200
    And the new wallet balance is reported
    And the audit log has an ALLOW row for action "WALLET_TOPUP"

  Scenario: A platform admin top-up without a step-up token is rejected
    Given a platform admin is signed in
    When they top up a user wallet by 1500 cents without a step-up token
    Then the response status is 403
    And the response code is "step_up_required"
    And no wallet credit was issued to billing
