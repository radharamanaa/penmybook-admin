Feature: Refund is idempotent on paymentId (AD8 / G14)
  POST /v1/admin/users/{userId}/refund called twice with the same paymentId MUST debit the
  wallet exactly once. The second call returns 200 with idempotentReplay=true; the billing
  service receives the request both times (admin-api does not de-duplicate locally — the
  billing service is the system-of-record for the idempotency check).

  Scenario: Replaying the same refund returns the same receipt with idempotentReplay=true
    Given a platform admin is signed in
    When they refund payment 11111111-1111-1111-1111-111111111111 of 2500 cents
    And they refund payment 11111111-1111-1111-1111-111111111111 of 2500 cents again
    Then both refund calls succeed
    And the wallet was only credited once for that payment
    And the second refund call reports idempotentReplay=true
