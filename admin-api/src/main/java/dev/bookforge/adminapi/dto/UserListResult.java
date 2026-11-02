package dev.bookforge.adminapi.dto;

import java.util.List;

/**
 * One page of the staff signups list ({@code GET /v1/admin/users}), and how many users match in all.
 */
public record UserListResult(List<UserSearchResultEntry> users, long total) {}
