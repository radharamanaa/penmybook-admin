Feature: Staff residual queue is platform-admin only and step-up gated
  Residuals are money that could not be auto-returned to a card. Settling a refund-request
  residual closes PARTIALLY_SETTLED without crediting (the Paddle refusal rolled the debit
  back). Account-deletion residuals still credit the wallet. Billing HTTP 200 is success
  even when settled=false (already-resolved replay).

  Scenario: A platform admin lists unresolved residuals
    Given a platform admin is signed in
    When they view the residual queue
    Then the response status is 200
    And the residual queue is not degraded

  Scenario: A billing outage degrades the residual queue instead of looking like it is clear
    Given a platform admin is signed in
    When they view the residual queue while billing is unavailable
    Then the response status is 200
    And the residual queue is marked degraded rather than empty-and-fine

  Scenario: A non-admin is refused the residual queue
    Given a non-admin user is signed in
    When they view the residual queue
    Then the response status is 403

  Scenario: A platform admin settles a residual and an ALLOW row is written
    Given a platform admin is signed in
    When they settle a residual
    Then the response status is 200
    And the audit log has an ALLOW row for action "REFUND_RESIDUAL_SETTLE"

  Scenario: An already-resolved residual replay is still HTTP 200
    Given a platform admin is signed in
    When they settle a residual that billing already resolved
    Then the response status is 200
    And the audit log has an ALLOW row for action "REFUND_RESIDUAL_SETTLE"

  Scenario: Starting OTP for a residual settle reaches entitlements
    Given a platform admin is signed in
    When they start an OTP challenge to settle a residual
    Then the response status is 201
    And entitlements received OTP action "SETTLE_REFUND_RESIDUAL"
    And entitlements received a residual-settle OTP bound only to the residual id
