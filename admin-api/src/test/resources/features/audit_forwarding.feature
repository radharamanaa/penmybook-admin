Feature: Admin writes really delegate to the downstream callee
  An admin write must actually FORWARD the staff identity, payload, and step-up token to the
  entitlements admin endpoint — not just return success because a stub said so. And when the
  downstream answers with a transport-level failure (4xx / 5xx) instead of a clean success:false
  body, admin-api must surface that as a failure, not as success.

  Scenario: Granting credit forwards the staff identity, payload and step-up token to entitlements
    Given the entitlements admin endpoint is reset
    When the admin grants 500 cents with a valid step-up token for staff observation
    Then the action succeeds
    And the entitlements admin endpoint was called with the staff identity, amountCents, reason and step-up token

  Scenario: A 500 response from the entitlements admin endpoint is reported as a failure
    Given the entitlements admin endpoint will return HTTP 500
    When the admin grants 500 cents with a valid step-up token
    Then the action is reported as failed and not as a success

  Scenario: A 400 response from the entitlements admin endpoint is reported as a failure
    Given the entitlements admin endpoint will return HTTP 400
    When the admin grants 500 cents with a valid step-up token
    Then the action is reported as failed and not as a success
