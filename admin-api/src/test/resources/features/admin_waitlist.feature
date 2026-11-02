Feature: Closed-beta waitlist admin surface (admin control plane D11)
  The platform admin approves testers by email and tunes the weekly approval budget. Every
  waitlist endpoint is hasRole('PLATFORM_ADMIN'); a non-admin is rejected with 403 + a DENY
  audit row. A real approval / config change writes an ALLOW audit row.

  Scenario: user search surfaces account status so the operator can spot waitlisted testers
    Given a platform admin is signed in
    When they search users by email "target@example.com"
    Then the response status is 200
    And the search result includes account status "WAITLISTED"

  Scenario: the signups list passes every filter on, caps the page size and carries the total
    Given a platform admin is signed in
    When they list signups filtered to waitlisted users
    Then the response status is 200
    And the signups list shows "target@example.com" of 128 users

  Scenario: a non-admin cannot see the signups list
    Given a non-admin user is signed in
    When they list signups filtered to waitlisted users
    Then the response status is 403

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

  # ─────────── pre-admit by email (2026-09-03) ───────────

  Scenario: a non-admin cannot pre-admit an email
    Given a non-admin user is signed in
    When they attempt to pre-admit the email "invitee@example.com"
    Then the response status is 403

  Scenario: a platform admin pre-admits an email; the mesh call carries the admin-api identity and is audited
    Given a platform admin is signed in
    When they pre-admit the email " Invitee@Example.com "
    Then the response status is 200
    And the pre-admit response is admitted
    And the entitlements pre-admit call carried the service subject "service:admin_api" and email "invitee@example.com"
    And the audit log has an ALLOW row for action "PRE_ADMIT_EMAIL"

  Scenario: a rejected pre-admit is a 200 with a reason and writes no ALLOW row
    Given a platform admin is signed in
    And entitlements rejects pre-admits with reason "weekly_limit_reached"
    When they pre-admit the email "invitee@example.com"
    Then the response status is 200
    And the pre-admit response is rejected with reason "weekly_limit_reached"
    And the audit log has no ALLOW row for action "PRE_ADMIT_EMAIL"

  Scenario: a non-admin cannot list pending pre-admits
    Given a non-admin user is signed in
    When they list pending pre-admits
    Then the response status is 403

  Scenario: a platform admin lists pending pre-admits
    Given a platform admin is signed in
    When they list pending pre-admits
    Then the response status is 200
    And the pending pre-admits include "pending@example.com"
