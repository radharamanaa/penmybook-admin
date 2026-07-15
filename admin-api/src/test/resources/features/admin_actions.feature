Feature: Audited admin actions
  Writes change downstream state and record an audit row in the same transaction.

  Scenario: Granting credit records an audit entry
    When the admin grants 500 cents with a valid step-up token
    Then the action succeeds
    And an audit entry is recorded

  Scenario: Suspending a user sets the account status to suspended
    When the admin suspends a user with a valid step-up token
    Then the user account status is "SUSPENDED"

  Scenario: Refunding a payment delegates to billing and is audited
    When the admin refunds a payment with a valid step-up token
    Then the action succeeds
    And an audit entry is recorded

  Scenario: An audit-insert failure rolls back the whole action
    Given audit insertion will fail
    When the admin grants 500 cents with a valid step-up token
    Then the action is rolled back
