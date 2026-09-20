package com.focusguard.accessibility.website.redirection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AddressBarRedirectionActionsPolicyTest {
    @Test
    fun `focused editable editor remains eligible for submission`() {
        assertThat(
            AddressBarRedirectionActions.canUseEditorForCertifiedSubmission(
                editable = true,
                focused = true,
                textCertified = false
            )
        ).isTrue()
    }

    @Test
    fun `certified redirect remains eligible when browser collapses focus`() {
        assertThat(
            AddressBarRedirectionActions.canUseEditorForCertifiedSubmission(
                editable = true,
                focused = false,
                textCertified = true
            )
        ).isTrue()
    }

    @Test
    fun `unfocused editor without certified redirect is rejected`() {
        assertThat(
            AddressBarRedirectionActions.canUseEditorForCertifiedSubmission(
                editable = true,
                focused = false,
                textCertified = false
            )
        ).isFalse()
    }

    @Test
    fun `active editing requires actual focus`() {
        assertThat(
            AddressBarRedirectionActions.isActiveAddressEdit(
                editable = true,
                focused = false
            )
        ).isFalse()
        assertThat(
            AddressBarRedirectionActions.isActiveAddressEdit(
                editable = true,
                focused = true
            )
        ).isTrue()
    }

    @Test
    fun `non editable node is rejected even when redirect text is certified`() {
        assertThat(
            AddressBarRedirectionActions.canUseEditorForCertifiedSubmission(
                editable = false,
                focused = false,
                textCertified = true
            )
        ).isFalse()
    }
}
