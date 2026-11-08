package com.kguard.app.ui.screens

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import com.kguard.app.R
import com.kguard.app.ui.components.KGPrimaryButton
import com.kguard.app.ui.components.KGTextField
import com.kguard.app.ui.components.KGuardLogo
import com.kguard.app.ui.theme.KGShapes
import com.kguard.app.ui.theme.KGuardTheme
import com.kguard.app.viewmodel.SessionViewModel

/**
 * The one auth screen. Parents sign in with Google only; child devices need NO account —
 * they pair with the 6-digit code the parent's dashboard generates, which creates an
 * anonymous device identity under the hood.
 */
@Composable
fun SignInScreen(
    session: SessionViewModel,
    onParentSignedIn: () -> Unit = {},
    onChildPaired: () -> Unit = {}
) {
    val busy by session.busy.collectAsState()
    val error by session.error.collectAsState()
    val context = LocalContext.current
    val clientId = remember(context) { context.getString(R.string.default_web_client_id) }
    val scope = rememberCoroutineScope()

    SignInContent(
        busy = busy,
        error = error,
        onGoogleSignIn = {
            Log.d("KGuard", "UI: User clicked Sign In with Google (parent)")
            scope.launch {
                try {
                    val credentialManager = CredentialManager.create(context)
                    val googleIdOption = GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(clientId)
                        .build()

                    val request = GetCredentialRequest.Builder()
                        .addCredentialOption(googleIdOption)
                        .build()

                    val result = credentialManager.getCredential(context, request)
                    val credential = result.credential
                    if (credential is androidx.credentials.CustomCredential &&
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                        Log.d("KGuard", "UI: Google credential retrieved successfully, signing in with Firebase...")
                        session.signInWithCredential(firebaseCredential, onDone = onParentSignedIn)
                    } else {
                        Log.e("KGuard", "UI: Unexpected credential type received from Credential Manager")
                    }
                } catch (e: Exception) {
                    Log.e("KGuard", "UI: Google Sign-In error", e)
                }
            }
        },
        onPairWithCode = { code ->
            session.pairChildDevice(code, onDone = onChildPaired)
        }
    )
}

@Composable
fun SignInContent(
    busy: Boolean,
    error: String?,
    onGoogleSignIn: () -> Unit = {},
    onPairWithCode: (String) -> Unit = {}
) {
    var isChildMode by rememberSaveable { mutableStateOf(false) }
    var otpCode by rememberSaveable { mutableStateOf("") }
    val validOtp = otpCode.trim().length == 6

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        KGuardLogo(modifier = Modifier.size(80.dp))
        Spacer(Modifier.height(8.dp))
        Text("K-Guard", style = MaterialTheme.typography.headlineLarge)
        Text(
            if (isChildMode) "Set up this child's device" else "Sign in to your family",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(
                selected = !isChildMode,
                onClick = { isChildMode = false },
                shape = KGShapes.pill,
                label = { Text("Parent") },
                leadingIcon = { androidx.compose.material3.Icon(Icons.Default.SupervisorAccount, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            FilterChip(
                selected = isChildMode,
                onClick = { isChildMode = true },
                shape = KGShapes.pill,
                label = { Text("Child device") },
                leadingIcon = { androidx.compose.material3.Icon(Icons.Default.ChildCare, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(20.dp))

        if (busy) {
            CircularProgressIndicator()
        } else if (!isChildMode) {
            Text(
                "One tap with your Google account — no passwords to forget.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            KGPrimaryButton(
                text = "Continue with Google",
                onClick = { onGoogleSignIn() },
                modifier = Modifier.fillMaxWidth(),
                leading = {
                    Image(
                        painter = painterResource(R.drawable.google_g),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        } else {
            Text(
                "No account needed. Enter the 6-digit code your parent's dashboard generated — it links this phone to your family securely.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            KGTextField(
                value = otpCode,
                onValueChange = { if (it.length <= 6) otpCode = it.filter { c -> c.isDigit() } },
                label = "Pairing code",
                keyboardType = KeyboardType.Number,
                isError = error != null,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            KGPrimaryButton(
                text = "Pair this device",
                onClick = { onPairWithCode(otpCode.trim()) },
                enabled = validOtp,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Your family's alerts and settings are stored securely and protected with age-aware controls.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SignInScreenPreview() {
    KGuardTheme {
        SignInContent(
            busy = false,
            error = null,
            onGoogleSignIn = {},
            onPairWithCode = {}
        )
    }
}
