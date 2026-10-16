Feature: Force-resolve reversed is platform-admin only and step-up bound
  POST /v1/admin/users/{userId}/payments/{paymentId}/force-resolve-reversed restores a
  PENDING_CARD_REFUND after Stripe reversed the refund. It uses the refund OTP action with
  forceResolve=true so a payout token cannot be replayed. Webhooks do not auto-restore.

  Scenario: A non-admin user is rejected with 403 and a DENY audit row is written
    Given a non-admin user is signed in
    When they attempt to force-resolve a reversed payment
    Then the response status is 403
    And the audit log has a DENY row for action "FORCE_RESOLVE_REVERSED"

  Scenario: A platform admin force-resolves a reversed payment and an ALLOW row is written
    Given a platform admin is signed in
    When they force-resolve a reversed payment
    Then the response status is 200
    And a force-resolve receipt is returned
    And the audit log has an ALLOW row for action "FORCE_RESOLVE_REVERSED"

  Scenario: A payout step-up token cannot force-resolve the same payment
    Given a platform admin is signed in
    When they force-resolve a reversed payment using a payout token
    Then the response status is 403
    And billing was not asked to force-resolve
