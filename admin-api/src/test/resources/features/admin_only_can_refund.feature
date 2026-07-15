Feature: Refund endpoint is platform-admin only
  POST /v1/admin/users/{userId}/refund MUST be gated by hasRole('PLATFORM_ADMIN'); non-admin
  callers receive HTTP 403 + DENY audit row; admins receive a refund receipt + ALLOW row.

  Scenario: A non-admin user is rejected with 403 and a DENY audit row is written
    Given a non-admin user is signed in
    When they attempt to refund a payment of 2500 cents
    Then the response status is 403
    And the audit log has a DENY row for action "REFUND"

  Scenario: A platform admin refunds a payment successfully and an ALLOW row is written
    Given a platform admin is signed in
    When they refund a payment of 2500 cents
    Then the response status is 200
    And a refund receipt is returned
    And the audit log has an ALLOW row for action "REFUND"
