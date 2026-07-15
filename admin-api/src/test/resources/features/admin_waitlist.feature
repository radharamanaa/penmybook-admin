Feature: Closed-beta waitlist admin surface (admin control plane D11)
  The platform admin approves testers by email and tunes the weekly approval budget. Every
  waitlist endpoint is hasRole('PLATFORM_ADMIN'); a non-admin is rejected with 403 + a DENY
  audit row. A real approval / config change writes an ALLOW audit row.

  Scenario: user search surfaces account status so the operator can spot waitlisted testers
    Given a platform admin is signed in
    When they search users by email "target@example.com"
    Then the response status is 200
    And the search result includes account status "WAITLISTED"

  Scenario: a non-admin cannot approve from the waitlist
    Given a non-admin user is signed in
    When they attempt to approve the target user from the waitlist
    Then the response status is 403
    And the audit log has a DENY row for action "WAITLIST_ADMIT"

  Scenario: a platform admin approves a waitlisted user and an ALLOW row is written
    Given a platform admin is signed in
    When they approve the target user from the waitlist
    Then the response status is 200
    And the approval reports admitted true
    And the audit log has an ALLOW row for action "WAITLIST_ADMIT"

  Scenario: a non-admin cannot change the waitlist config
    Given a non-admin user is signed in
    When they attempt to set the weekly approval limit to 100
    Then the response status is 403
    And the audit log has a DENY row for action "WAITLIST_CONFIG"

  Scenario: a platform admin reads and raises the weekly approval limit
    Given a platform admin is signed in
    When they read the waitlist config
    Then the response status is 200
    And the waitlist config reports weekly limit 50
    When they set the weekly approval limit to 100
    Then the response status is 200
    And the waitlist config reports weekly limit 100
    And the audit log has an ALLOW row for action "WAITLIST_CONFIG"
