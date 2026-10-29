Feature: Staff list of money a refund or dispute could not recover is platform-admin only
  A refund or dispute that could not take money back from the wallet, because it was already
  spent, records it on the payment as unrecovered. The list is a read: no step-up, no audit row.
  A billing outage is reported as degraded, never as an empty list.

  Scenario: A platform admin lists payments with unrecovered money
    Given a platform admin is signed in
    When they view the unrecovered payments
    Then the response status is 200
    And the unrecovered payments list is not degraded and shows billing's row

  Scenario: A billing outage degrades the list instead of looking like nothing is unrecovered
    Given a platform admin is signed in
    When they view the unrecovered payments while billing is unavailable
    Then the response status is 200
    And the unrecovered payments list is marked degraded rather than empty-and-fine

  Scenario: A non-admin is refused the unrecovered payments
    Given a non-admin user is signed in
    When they view the unrecovered payments
    Then the response status is 403
