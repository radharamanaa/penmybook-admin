Feature: Payments health admin relay
  GET /v1/admin/payments/health relays billing's payment-integration health panel.
  hasRole('PLATFORM_ADMIN') is load-bearing — a non-admin is rejected with 403.

  Scenario: a platform admin reads payments health and the body is relayed
    Given a platform admin is signed in
    When they read payments health
    Then the response status is 200
    And the payments health reports stuck refunds 1
    And the payments health reports stuck top-ups 2
    And the payments health reports abandoned top-ups 3
    And the payments health reports webhooks 24h 4

  Scenario: a non-admin cannot read payments health
    Given a non-admin user is signed in
    When they attempt to read payments health
    Then the response status is 403
