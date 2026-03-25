package no.ringlog.app.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.ringlog.app.R

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LoginScreen(onLoggedIn: () -> Unit, vm: LoginViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val autofill = LocalAutofill.current
    val autofillTree = LocalAutofillTree.current

    LaunchedEffect(state) { if (state is LoginViewModel.State.Success) onLoggedIn() }

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val usernameNode = remember {
        AutofillNode(autofillTypes = listOf(AutofillType.Username), onFill = { username = it })
    }
    val passwordNode = remember {
        AutofillNode(autofillTypes = listOf(AutofillType.Password), onFill = { password = it })
    }
    autofillTree += usernameNode
    autofillTree += passwordNode

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🐦 ${stringResource(R.string.app_name)}", fontSize = 32.sp, fontWeight = FontWeight.Bold,
             color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.bird_registry), style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(Modifier.height(40.dp))

        OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text(stringResource(R.string.username)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { usernameNode.boundingBox = it.boundsInWindow() }
                .onFocusChanged { fs ->
                    if (fs.isFocused) autofill?.requestAutofillForNode(usernameNode)
                    else autofill?.cancelAutofillForNode(usernameNode)
                },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { passwordNode.boundingBox = it.boundsInWindow() }
                .onFocusChanged { fs ->
                    if (fs.isFocused) autofill?.requestAutofillForNode(passwordNode)
                    else autofill?.cancelAutofillForNode(passwordNode)
                },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.login(username, password) }),
        )
        Spacer(Modifier.height(24.dp))

        if (state is LoginViewModel.State.Error) {
            Text((state as LoginViewModel.State.Error).msg,
                 color = MaterialTheme.colorScheme.error,
                 style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = { vm.login(username, password) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = state !is LoginViewModel.State.Loading,
        ) {
            if (state is LoginViewModel.State.Loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.log_in))
        }
    }
}
