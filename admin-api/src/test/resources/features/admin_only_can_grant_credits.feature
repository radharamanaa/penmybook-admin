Feature: Grant-credits endpoint is platform-admin only and step-up gated
  POST /v1/admin/users/{userId}/grant-credits MUST be gated by hasRole('PLATFORM_ADMIN').
  Unified-wallet money model (D7 / P12 / UF-04): a grant now mints spendable wallet MONEY
  (amountCents, an ADMIN_CREDIT wallet credit), so it is ALSO step-up (OTP) gated — the SPA
  must present a valid X-Step-Up-Token bound over the exact amount. The grant never flips
  has_ever_paid (N2 / D11 — admin generosity must not disable the nurture email).

  Scenario: A non-admin user is rejected with 403 and a DENY audit row is written
    Given a non-admin user is signed in
    When they attempt to grant 500 cents to a user wallet
    Then the response status is 403
    And the audit log has a DENY row for action "GRANT_CREDITS"

  Scenario: A platform admin grants wallet money with a valid step-up token
    Given a platform admin is signed in
    And the target user has has_ever_paid=false
    When they grant 500 cents to a user wallet
    Then the response status is 200
    And the receipt reports the granted amount in cents
    And the audit log has an ALLOW row for action "GRANT_CREDITS"

  Scenario: A platform admin grant without a step-up token is rejected
    Given a platform admin is signed in
    When they grant 500 cents to a user wallet without a step-up token
    Then the response status is 403
    And the response code is "step_up_required"
    And no wallet credit was issued to entitlements

  Scenario: A platform admin grant with a step-up token bound to a different amount is rejected
    Given a platform admin is signed in
    When they grant 500 cents to a user wallet with a step-up token bound to a different amount
    Then the response status is 403
    And the response code is "step_up_invalid"
    And no wallet credit was issued to entitlements
