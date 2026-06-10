package com.vibecode.companion.data.api

import kotlinx.serialization.Serializable

/**
 * DTOs for the Cursor Cloud Agents API v1.
 * Source of truth: docs/cloud-agents-openapi.yaml (fetched 2026-06-10).
 * Status fields are kept as raw strings so new server-side values never crash parsing;
 * use [RunStatus] / [AgentStatus] constants for comparisons.
 */

object AgentStatus {
    const val ACTIVE = "ACTIVE"
    const val ARCHIVED = "ARCHIVED"
}

object RunStatus {
    const val CREATING = "CREATING"
    const val RUNNING = "RUNNING"
    const val FINISHED = "FINISHED"
    const val ERROR = "ERROR"
    const val CANCELLED = "CANCELLED"
    const val EXPIRED = "EXPIRED"

    val TERMINAL = setOf(FINISHED, ERROR, CANCELLED, EXPIRED)
    fun isTerminal(status: String?): Boolean = status in TERMINAL
}

@Serializable
data class PromptBody(val text: String)

@Serializable
data class ModelParam(val id: String, val value: String)

@Serializable
data class ModelRef(val id: String, val params: List<ModelParam>? = null)

@Serializable
data class RepoConfig(
    val url: String,
    val startingRef: String? = null,
    val prUrl: String? = null,
)

@Serializable
data class AgentEnv(val type: String = "cloud", val name: String? = null)

@Serializable
data class RunGitBranch(
    val repoUrl: String,
    val branch: String? = null,
    val prUrl: String? = null,
)

@Serializable
data class RunGit(val branches: List<RunGitBranch> = emptyList())

@Serializable
data class Run(
    val id: String,
    val agentId: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val durationMs: Long? = null,
    val result: String? = null,
    val git: RunGit? = null,
)

/** Covers both AgentSummary (list items) and Agent (detail) — detail-only fields are nullable. */
@Serializable
data class CloudAgent(
    val id: String,
    val name: String? = null,
    val status: String,
    val env: AgentEnv? = null,
    val url: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val latestRunId: String? = null,
    val repos: List<RepoConfig>? = null,
    val workOnCurrentBranch: Boolean? = null,
    val autoCreatePR: Boolean? = null,
    val skipReviewerRequest: Boolean? = null,
)

object AgentMode {
    const val AGENT = "agent"
    const val PLAN = "plan"
}

@Serializable
data class CreateAgentRequest(
    val prompt: PromptBody,
    val model: ModelRef? = null,
    val name: String? = null,
    val repos: List<RepoConfig>? = null,
    val workOnCurrentBranch: Boolean? = null,
    val autoCreatePR: Boolean? = null,
    val mode: String? = null,
)

@Serializable
data class CreateRunRequest(
    val prompt: PromptBody,
    val mode: String? = null,
)

@Serializable
data class CreateAgentResponse(val agent: CloudAgent, val run: Run)

@Serializable
data class CreateRunResponse(val run: Run)

@Serializable
data class ListAgentsResponse(val items: List<CloudAgent>, val nextCursor: String? = null)

@Serializable
data class ListRunsResponse(val items: List<Run>, val nextCursor: String? = null)

@Serializable
data class IdResponse(val id: String)

@Serializable
data class ApiKeyInfo(
    val apiKeyName: String,
    val createdAt: String,
    val userId: Long? = null,
    val userEmail: String? = null,
    val userFirstName: String? = null,
    val userLastName: String? = null,
)

@Serializable
data class ModelListItem(
    val id: String,
    val displayName: String,
    val description: String? = null,
    val aliases: List<String>? = null,
)

@Serializable
data class ListModelsResponse(val items: List<ModelListItem>)

@Serializable
data class Repository(val url: String)

@Serializable
data class ListRepositoriesResponse(val items: List<Repository>)

@Serializable
data class ApiErrorDetail(
    val code: String,
    val message: String,
    val helpUrl: String? = null,
)

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail)

class CursorApiException(
    val httpStatus: Int,
    val code: String,
    override val message: String,
    val helpUrl: String? = null,
) : Exception(message) {
    val isAuthError: Boolean get() = httpStatus == 401
    val isPlanError: Boolean get() = httpStatus == 403
    val isRateLimited: Boolean get() = httpStatus == 429
}
