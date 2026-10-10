package app.kairo.android.ui.login

import app.kairo.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import app.kairo.android.InviteLink
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairo.android.AppGraph
import app.kairo.android.BuildConfig
import app.kairo.android.ui.common.friendly
import app.kairo.android.ui.components.KairoOrb
import app.kairo.android.ui.components.OrbState
import app.kairo.android.ui.theme.MonoStyle
import app.kairo.core.net.KairoApi
import kotlinx.coroutines.launch

/** Sign-in steps: sign in with an account, enter a code, join the family or set a new password. */
private enum class Step { Login, Code, Join, Reset }

private const val MIN_PASSWORD = 10

@Composable
fun LoginScreen(graph: AppGraph, invite: InviteLink? = null, onInviteConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    var step by rememberSaveable { mutableStateOf(Step.Login) }
    var server by rememberSaveable { mutableStateOf(BuildConfig.DEFAULT_SERVER) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    // What GET /auth/invite/{code} returned
    var household by rememberSaveable { mutableStateOf("") }
    var targetEmail by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    LaunchedEffect(Unit) { graph.session.serverUrl()?.let { server = it } }
    BackHandler(step != Step.Login && !loading) {
        error = null
        step = if (step == Step.Code) Step.Login else Step.Code
    }

    fun run(block: suspend () -> Unit) {
        loading = true; error = null
        scope.launch {
            try { block() } catch (e: Exception) { error = e.friendly(context) } finally { loading = false }
        }
    }

    fun login() {
        if (email.isBlank() || password.isBlank()) { error = resources.getString(R.string.login_err_credentials); return }
        run { graph.api.login(server, email, password, deviceName) }
    }

    fun preview() {
        val normalized = KairoApi.normalizeCode(code)
        if (normalized.length != 9) { error = resources.getString(R.string.login_err_code_format); return }
        run {
            val p = graph.api.invitePreview(server, normalized)
            code = normalized
            household = p.householdName
            name = p.name
            targetEmail = p.email
            password = ""
            step = if (p.kind == "reset") Step.Reset else Step.Join
        }
    }

    fun join() {
        if (step == Step.Join && name.isBlank()) { error = resources.getString(R.string.login_err_name); return }
        if (step == Step.Join && email.isBlank()) { error = resources.getString(R.string.login_err_email); return }
        if (password.length < MIN_PASSWORD) { error = resources.getString(R.string.login_err_password_length, MIN_PASSWORD); return }
        run {
            if (step == Step.Join) graph.api.join(server, code, email, password, name, deviceName)
            else graph.api.join(server, code, null, password, null, deviceName)
        }
    }

    // Opened from an invite link (https://host/join?code=... or the legacy /unirse?codigo=...): prefill and look it up
    LaunchedEffect(invite) {
        if (invite == null) return@LaunchedEffect
        invite.server?.let { server = it }
        code = invite.code
        step = Step.Code
        onInviteConsumed()
        run {
            val p = graph.api.invitePreview(server, invite.code)
            household = p.householdName
            name = p.name
            targetEmail = p.email
            password = ""
            step = if (p.kind == "reset") Step.Reset else Step.Join
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                KairoOrb(state = if (loading) OrbState.Thinking else OrbState.Idle, level = 0f, size = 96.dp)
                AnimatedContent(step, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "step") { current ->
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        when (current) {
                            Step.Login -> {
                                Title(stringResource(R.string.app_name), stringResource(R.string.login_tagline), big = true)
                                Spacer(Modifier.height(8.dp))
                                Field(stringResource(R.string.login_server), server, { server = it }, KeyboardType.Uri, ImeAction.Next)
                                Field(stringResource(R.string.login_email), email, { email = it }, KeyboardType.Email, ImeAction.Next)
                                PasswordField(stringResource(R.string.login_password), password, { password = it }, onDone = ::login)
                            }
                            Step.Code -> {
                                Title(stringResource(R.string.login_code_title), stringResource(R.string.login_code_body))
                                Field(stringResource(R.string.login_server), server, { server = it }, KeyboardType.Uri, ImeAction.Next)
                                Field(
                                    stringResource(R.string.login_code), code, { code = it.take(12) }, KeyboardType.Ascii, ImeAction.Go,
                                    modifier = Modifier.testTag("invite-code"), placeholder = "ABCD-2345",
                                    textStyle = MonoStyle.copy(fontSize = 20.sp, letterSpacing = 2.sp), capitalize = true, onAction = ::preview,
                                )
                            }
                            Step.Join -> {
                                Title(stringResource(R.string.login_join_title, household), stringResource(R.string.login_join_body))
                                Field(stringResource(R.string.login_name), name, { name = it }, KeyboardType.Text, ImeAction.Next)
                                Field(stringResource(R.string.login_email), email, { email = it }, KeyboardType.Email, ImeAction.Next)
                                PasswordField(stringResource(R.string.login_password), password, { password = it }, helper = stringResource(R.string.login_password_hint, MIN_PASSWORD), onDone = ::join)
                            }
                            Step.Reset -> {
                                Title(
                                    if (name.isBlank()) stringResource(R.string.login_reset_title_again) else stringResource(R.string.login_reset_title, name),
                                    targetEmail?.let { stringResource(R.string.login_reset_body_email, it) } ?: stringResource(R.string.login_reset_body),
                                )
                                PasswordField(stringResource(R.string.login_new_password), password, { password = it }, helper = stringResource(R.string.login_password_hint, MIN_PASSWORD), onDone = ::join)
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Button(
                    onClick = { when (step) { Step.Login -> login(); Step.Code -> preview(); Step.Join, Step.Reset -> join() } },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                ) {
                    if (loading) LoadingIndicator(Modifier.height(28.dp))
                    else Text(stringResource(when (step) { Step.Login -> R.string.login_action_sign_in; Step.Code -> R.string.login_action_continue; Step.Join -> R.string.login_action_join; Step.Reset -> R.string.login_action_save }), style = MaterialTheme.typography.titleMedium)
                }
                when (step) {
                    Step.Login -> OutlinedButton(onClick = { error = null; step = Step.Code }, enabled = !loading, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.login_code_title)) }
                    Step.Code -> TextButton(onClick = { error = null; step = Step.Login }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.login_have_account)) }
                    else -> TextButton(onClick = { error = null; step = Step.Code }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.login_other_code)) }
                }
                Text(
                    stringResource(R.string.login_footnote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Title(title: String, body: String, big: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = if (big) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    type: KeyboardType,
    ime: ImeAction,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    textStyle: TextStyle? = null,
    capitalize: Boolean = false,
    onAction: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = placeholder?.let { { Text(it, style = textStyle ?: MaterialTheme.typography.bodyLarge) } },
            textStyle = textStyle ?: MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(
                keyboardType = type, imeAction = ime,
                capitalization = if (capitalize) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
                autoCorrectEnabled = !capitalize && type == KeyboardType.Text,
            ),
            keyboardActions = KeyboardActions(onGo = { onAction?.invoke() }),
            shape = MaterialTheme.shapes.small,
        )
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit, helper: String? = null, onDone: () -> Unit) {
    var show by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            supportingText = helper?.let { { Text(it) } },
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) stringResource(R.string.password_hide) else stringResource(R.string.password_show))
                }
            },
            shape = MaterialTheme.shapes.small,
        )
    }
}
