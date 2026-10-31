Feature: Staff Money alerts list and acknowledge are platform-admin only
  Billing raises money alerts (a refund shortfall, a dispute in another currency, a double payout)
  and emails the critical ones. Staff read them on the Money alerts screen and close each with a
  note. The list is a read: no step-up, no audit row. A billing outage is reported as degraded,
  never as an empty list. Closing an alert moves no money, so it is not step-up gated; it is
  audited.

  Scenario: A platform admin lists the open money alerts
    Given a platform admin is signed in
    When they view the open money alerts
    Then the response status is 200
    And the money alert list is not degraded and shows billing's open alerts
    And billing was asked for the open alerts

  Scenario: All alerts adds the closed ones, newest raised first
    Given a platform admin is signed in
    When they view all money alerts
    Then the response status is 200
    And the money alert list shows the closed alert before the open ones

  Scenario: The severity filter keeps only that severity
    Given a platform admin is signed in
    When they view the open money alerts with severity "CRITICAL"
    Then the response status is 200
    And every money alert listed is "CRITICAL"

  Scenario: An unknown filter is refused
    Given a platform admin is signed in
    When they view money alerts with status "EVERYTHING"
    Then the response status is 400

  Scenario: A billing outage degrades the list instead of looking like nothing is wrong
    Given a platform admin is signed in
    When they view the open money alerts while billing is unavailable
    Then the response status is 200
    And the money alert list is marked degraded rather than empty-and-fine

  Scenario: A non-admin is refused the money alerts
    Given a non-admin user is signed in
    When they view the open money alerts
    Then the response status is 403

  Scenario: A platform admin acknowledges an alert; billing gets who and why, and it is audited
    Given a platform admin is signed in
    When they acknowledge the money alert with note "Refunded by hand in Stripe"
    Then the response status is 200
    And billing was told the admin acknowledged it with note "Refunded by hand in Stripe"
    And the audit log has an ALLOW row for action "MONEY_ALERT_ACKNOWLEDGE"

  Scenario: An alert another admin already closed is returned unchanged and not audited again
    Given a platform admin is signed in
    And another admin already acknowledged the money alert
    When they acknowledge the money alert with note "Me too"
    Then the response status is 200
    And the audit log has no ALLOW row for action "MONEY_ALERT_ACKNOWLEDGE"

  Scenario: Acknowledging without a note is refused before billing is called
    Given a platform admin is signed in
    When they acknowledge the money alert with note " "
    Then the response status is 400
    And billing was not asked to acknowledge the money alert

  Scenario: A non-admin cannot acknowledge an alert
    Given a non-admin user is signed in
    When they acknowledge the money alert with note "Refunded by hand in Stripe"
    Then the response status is 403
    And the audit log has a DENY row for action "MONEY_ALERT_ACKNOWLEDGE"
