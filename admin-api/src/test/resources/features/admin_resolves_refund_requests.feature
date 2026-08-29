Feature: Resolving a user's refund request is platform-admin only and step-up gated
  A refund request is decided by an ADMIN, never automatically, and the ADMIN sets the final
  amount. The step-up token is bound over {requestId, decision, amountCents}, so a token
  authorised to return one amount cannot be replayed to return a larger one. The user-facing
  note is required on the write but is not in that hash.

  Scenario: A non-admin caller is rejected with 403 and a DENY audit row is written
    Given a non-admin user is signed in
    When they attempt to resolve a refund request approving 2000 cents
    Then the response status is 403
    And the audit log has a DENY row for action "REFUND_REQUEST_RESOLVE"

  Scenario: A platform admin approves a request and an ALLOW row is written
    Given a platform admin is signed in
    When they resolve a refund request approving 2000 cents
    Then the response status is 200
    And the receipt reports the amount billing actually refunded
    And the audit log has an ALLOW row for action "REFUND_REQUEST_RESOLVE"

  Scenario: An admin without a step-up token is refused
    Given a platform admin is signed in
    When they resolve a refund request approving 2000 cents without a step-up token
    Then the response status is 403

  Scenario: A step-up token bound to one amount cannot approve a larger one
    Given a platform admin is signed in
    When they resolve a refund request with a token bound to 2000 cents but approve 50000 cents
    Then the response status is 403

  Scenario: Starting OTP for a refund-request resolve reaches entitlements
    Given a platform admin is signed in
    When they start an OTP challenge to resolve a refund request
    Then the response status is 201
    And entitlements received OTP action "RESOLVE_REFUND_REQUEST"

  Scenario: The queue shows staff what they need to decide
    Given a platform admin is signed in
    When they view the refund-request queue
    Then the response status is 200
    And the queue shows both the quoted and the live refundable amount

  Scenario: A billing outage degrades the queue instead of looking like it is clear
    Given a platform admin is signed in
    When they view the refund-request queue while billing is unavailable
    Then the response status is 200
    And the queue is marked degraded rather than empty-and-fine

  Scenario: The queue names the owner when identity lookup succeeds
    Given a platform admin is signed in
    When they view the refund-request queue whose owner is known
    Then the response status is 200
    And the queue names the owner

  Scenario: The queue names an org owner from the lean identity endpoint
    Given a platform admin is signed in
    When they view the refund-request queue whose owner is an org
    Then the response status is 200
    And the queue names the org owner

  Scenario: An identity outage degrades names without looking like billing is down
    Given a platform admin is signed in
    When they view the refund-request queue while identity lookup is down
    Then the response status is 200
    And the queue is marked identity-degraded with owner UUIDs still present

  Scenario: An unsupported queue status is refused locally
    Given a platform admin is signed in
    When they view the refund-request queue filtered to "NOPE"
    Then the response status is 400

  Scenario: Retired Approving and Partially settled filters are refused locally
    Given a platform admin is signed in
    When they view the refund-request queue filtered to "APPROVING"
    Then the response status is 400

  Scenario: The queue can be filtered to PAYING
    Given a platform admin is signed in
    When they view the refund-request queue filtered to "PAYING"
    Then the response status is 200

  Scenario: The queue can be filtered to SETTLED
    Given a platform admin is signed in
    When they view the refund-request queue filtered to "SETTLED"
    Then the response status is 200

  Scenario: A billing timeout on resolve is a 502 telling the operator to refresh
    Given a platform admin is signed in
    When they resolve a refund request but billing does not confirm
    Then the response status is 502
    And the body tells the operator to refresh and check the status
    And the audit log has a DENY row for the attempted resolve

  Scenario: Rejecting is step-up gated and audited like an approval
    Given a platform admin is signed in
    When they reject a refund request
    Then the response status is 200
    And the audit log has an ALLOW row for action "REFUND_REQUEST_RESOLVE"
