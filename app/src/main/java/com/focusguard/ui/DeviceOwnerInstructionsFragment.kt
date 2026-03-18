package com.focusguard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager

class DeviceOwnerInstructionsFragment : Fragment() {

    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private lateinit var statusTextView: TextView
    private lateinit var instructionsTextView: TextView
    private lateinit var copyCommandButton: Button
    private lateinit var closeButton: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_device_owner_instructions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        deviceOwnerManager = DeviceOwnerManager(requireContext())

        statusTextView = view.findViewById(R.id.statusTextView)
        instructionsTextView = view.findViewById(R.id.instructionsTextView)
        copyCommandButton = view.findViewById(R.id.copyCommandButton)
        closeButton = view.findViewById(R.id.closeButton)

        updateStatus()

        copyCommandButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Atenção - Remoção de Contas")
                .setMessage("O modo Device Owner falhará silenciosamente se você tiver contas Google/Samsung ativas no aparelho.\n\nVocê confirma que já EXCLUIU temporariamente todas as contas nas Configurações do Android?")
                .setPositiveButton("Sim, Contas Removidas") { _, _ -> copyAdbCommand() }
                .setNegativeButton("Cancelar", null)
                .setCancelable(false)
                .show()
        }

        closeButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun updateStatus() {
        val isDeviceOwner = deviceOwnerManager.isDeviceOwnerActive()
        val isDeviceAdmin = deviceOwnerManager.isDeviceAdminActive()

        val statusText = when {
            isDeviceOwner -> "✓ Device Owner Mode is ACTIVE"
            isDeviceAdmin -> "✓ Device Admin is ACTIVE (Awaiting Device Owner setup)"
            else -> "✗ Device Owner Mode is INACTIVE"
        }

        statusTextView.text = statusText

        val instructions = buildString {
            appendLine("=== Device Owner Mode Setup Instructions ===\n")
            
            if (isDeviceOwner) {
                appendLine("✓ Device Owner Mode is already active!")
                appendLine("\nYour device is now in Device Owner Mode.")
                appendLine("You can now use advanced blocking features.")
            } else if (isDeviceAdmin) {
                appendLine("Step 1: Device Admin is active ✓\n")
                appendLine("Step 2: Set as Device Owner using ADB\n")
                appendLine("Requirements:")
                appendLine("- Android Debug Bridge (ADB) installed on your computer")
                appendLine("- USB debugging enabled on your device")
                appendLine("- Device connected via USB\n")
                appendLine("Instruções:")
                appendLine("1. Conecte o celular ao Computador via USB")
                appendLine("2. Habilite Depuração USB nas Opções de Desenvolvedor")
                appendLine("3. Abra o Terminal CMD ou PowerShell no seu Computador")
                appendLine("4. Execute o comando Nuclear (Obrigatório --user 0 na Samsung/Xiaomi):\n")
                appendLine("adb shell dpm set-device-owner --user 0 ${requireContext().packageName}/com.focusguard.admin.FocusGuardDeviceAdminReceiver\n")
                appendLine("5. Aguarde o aviso de Sucesso (Success)")
                appendLine("6. Reinicie o FocusGuard\n")
                appendLine("Atenção: A ativação falhará se você tiver contas ou perfis de trabalho não removidos.")
            } else {
                appendLine("Step 1: Enable Device Admin\n")
                appendLine("1. Click 'Enable Device Owner Mode' button in the app")
                appendLine("2. Grant Device Admin permissions when prompted\n")
                appendLine("Step 2: Set as Device Owner using ADB\n")
                appendLine("Requirements:")
                appendLine("- Android Debug Bridge (ADB) installed on your computer")
                appendLine("- USB debugging enabled on your device")
                appendLine("- Device connected via USB\n")
                appendLine("Instruções:")
                appendLine("1. Conecte o celular ao Computador via USB")
                appendLine("2. Habilite Depuração USB nas Opções de Desenvolvedor")
                appendLine("3. Abra o Terminal no seu Computador")
                appendLine("4. Execute o comando Nuclear (Obrigatório --user 0 na Samsung/Xiaomi):\n")
                appendLine("adb shell dpm set-device-owner --user 0 ${requireContext().packageName}/com.focusguard.admin.FocusGuardDeviceAdminReceiver\n")
                appendLine("5. Aguarde o Success Message")
                appendLine("6. Reinicie o App FocusGuard")
            }
        }

        instructionsTextView.text = instructions
    }

    private fun copyAdbCommand() {
        val command = "adb shell dpm set-device-owner --user 0 ${requireContext().packageName}/com.focusguard.admin.FocusGuardDeviceAdminReceiver"
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("ADB Command", command)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Command copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
