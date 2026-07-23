Feature: Per-org pricing override is platform-admin only (R26 / T25)
  POST /v1/admin/orgs/{orgId}/pricing MUST be gated by hasRole('PLATFORM_ADMIN'). An org
  owner / admin MUST NOT be able to self-discount; T25 enforces this with a DENY audit row
  on the rejected attempt and an ALLOW audit row carrying old + new value when the
  platform admin succeeds. CHAPTER_GENERATE is priced per subtopic BY GENRE, so a chapter
  override MUST name a genre (else 400) so it targets a per_subtopic_<genre> row.

  Scenario: An org owner cannot self-discount; the attempt is recorded as DENY
    Given a non-admin user is signed in
    When they attempt to upsert per-org pricing for action "CHAPTER_GENERATE" genre "technical" at 100 cents
    Then the response status is 403
    And the audit log has a DENY row for action "UPSERT_ORG_PRICING"

  Scenario: A platform admin upserts a per-genre chapter price with an ALLOW audit row
    Given a platform admin is signed in
    When they upsert per-org pricing for action "CHAPTER_GENERATE" genre "technical" at 100 cents
    Then the response status is 200
    And the persisted pricing row is returned with the requested value
    And the audit log has an ALLOW row for action "UPSERT_ORG_PRICING"

  Scenario: A chapter price override without a genre is rejected
    Given a platform admin is signed in
    When they upsert per-org pricing for action "CHAPTER_GENERATE" at 100 cents
    Then the response status is 400
