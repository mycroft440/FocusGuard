package com.focusguard.data

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.focusguard.utils.FocusGuardLogger

sealed interface ForumGoogleSignInResult {
    data class Success(val idToken: String) : ForumGoogleSignInResult

    data object Cancelled : ForumGoogleSignInResult

    data object NoGoogleAccount : ForumGoogleSignInResult

    data object Failed : ForumGoogleSignInResult
}

/**
 * Asks Android for a Google ID token addressed to the forum backend. Only the token is
 * used: the backend checks it with Google once, keeps a salted hash of the account ID and
 * never stores the e-mail or the Google name.
 */
object ForumGoogleSignIn {
    private const val TAG = "ForumGoogleSignIn"

    suspend fun requestIdToken(activity: Activity, webClientId: String): ForumGoogleSignInResult {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
            .build()
        return try {
            val credential = CredentialManager.create(activity)
                .getCredential(activity, request)
                .credential
            if (
                credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                ForumGoogleSignInResult.Success(
                    GoogleIdTokenCredential.createFrom(credential.data).idToken
                )
            } else {
                FocusGuardLogger.log(TAG, "Unexpected credential type: ${credential.type}")
                ForumGoogleSignInResult.Failed
            }
        } catch (error: GetCredentialCancellationException) {
            ForumGoogleSignInResult.Cancelled
        } catch (error: NoCredentialException) {
            ForumGoogleSignInResult.NoGoogleAccount
        } catch (error: GetCredentialException) {
            FocusGuardLogger.logError(TAG, "Google sign-in failed", error)
            ForumGoogleSignInResult.Failed
        } catch (error: GoogleIdTokenParsingException) {
            FocusGuardLogger.logError(TAG, "Google ID token could not be read", error)
            ForumGoogleSignInResult.Failed
        }
    }
}
