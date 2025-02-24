// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.domain.token.AllocationToken.TokenType.DEFAULT_PROMO;
import static google.registry.model.tld.Tld.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.tld.Tld.TldState.PREDELEGATION;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.math.BigDecimal.ROUND_UNNECESSARY;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.Duration.standardMinutes;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.tld.Tld;
import google.registry.tldconfig.idn.IdnTableEnum;
import java.math.BigDecimal;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CreateTldCommand}. */
class CreateTldCommandTest extends CommandTestCase<CreateTldCommand> {

  @BeforeEach
  void beforeEach() {
    persistReservedList("common_abuse", "baa,FULLY_BLOCKED");
    persistReservedList("xn--q9jyb4c_abuse", "lamb,FULLY_BLOCKED");
    persistReservedList("tld_banned", "kilo,FULLY_BLOCKED", "lima,FULLY_BLOCKED");
    persistReservedList("soy_expurgated", "fireflies,FULLY_BLOCKED");
    persistPremiumList("xn--q9jyb4c", USD, "minecraft,USD 1000");
    command.validDnsWriterNames = ImmutableSet.of("VoidDnsWriter", "FooDnsWriter");
  }

  @Test
  void testSuccess() throws Exception {
    DateTime before = fakeClock.nowUtc();
    runCommandForced("xn--q9jyb4c", "--roid_suffix=Q9JYB4C", "--dns_writers=FooDnsWriter");
    DateTime after = fakeClock.nowUtc();

    Tld registry = Tld.get("xn--q9jyb4c");
    assertThat(registry).isNotNull();
    assertThat(registry.getAddGracePeriodLength()).isEqualTo(Tld.DEFAULT_ADD_GRACE_PERIOD);
    assertThat(registry.getCreationTime()).isIn(Range.closed(before, after));
    assertThat(registry.getDnsWriters()).containsExactly("FooDnsWriter");
    assertThat(registry.getTldState(registry.getCreationTime())).isEqualTo(PREDELEGATION);
    assertThat(registry.getRedemptionGracePeriodLength())
        .isEqualTo(Tld.DEFAULT_REDEMPTION_GRACE_PERIOD);
    assertThat(registry.getPendingDeleteLength()).isEqualTo(Tld.DEFAULT_PENDING_DELETE_LENGTH);
    assertThat(registry.getRegistryLockOrUnlockBillingCost())
        .isEqualTo(Tld.DEFAULT_REGISTRY_LOCK_OR_UNLOCK_BILLING_COST);
  }

  @Test
  void testSuccess_ttls() throws Exception {
    runCommandForced(
        "xn--q9jyb4c",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=FooDnsWriter",
        "--dns_a_plus_aaaa_ttl=PT300S",
        "--dns_ds_ttl=PT240S",
        "--dns_ns_ttl=PT180S");
    Tld registry = Tld.get("xn--q9jyb4c");
    assertThat(registry).isNotNull();
    assertThat(registry.getDnsAPlusAaaaTtl().get()).isEqualTo(standardMinutes(5));
    assertThat(registry.getDnsDsTtl().get()).isEqualTo(standardMinutes(4));
    assertThat(registry.getDnsNsTtl().get()).isEqualTo(standardMinutes(3));
  }

  @Test
  void testFailure_multipleArguments() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--roid_suffix=BLAH", "--dns_writers=VoidDnsWriter", "xn--q9jyb4c", "test"));
    assertThat(thrown).hasMessageThat().contains("Can't create more than one TLD at a time");
  }

  @Test
  void testFailure_multipleDuplicateArguments() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--roid_suffix=BLAH", "--dns_writers=VoidDnsWriter", "test", "test"));
    assertThat(thrown).hasMessageThat().contains("Can't create more than one TLD at a time");
  }

  @Test
  void testSuccess_initialTldStateFlag() throws Exception {
    runCommandForced(
        "--initial_tld_state=GENERAL_AVAILABILITY",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getTldState(DateTime.now(UTC)))
        .isEqualTo(GENERAL_AVAILABILITY);
  }

  @Test
  void testSuccess_initialRenewBillingCostFlag() throws Exception {
    runCommandForced(
        "--initial_renew_billing_cost=\"USD 42.42\"",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getStandardRenewCost(DateTime.now(UTC)))
        .isEqualTo(Money.of(USD, 42.42));
  }

  @Test
  void testSuccess_eapFeeSchedule() throws Exception {
    DateTime now = DateTime.now(UTC);
    DateTime tomorrow = now.plusDays(1);
    runCommandForced(
        String.format(
            "--eap_fee_schedule=\"%s=USD 0.00,%s=USD 50.00,%s=USD 10.00\"",
            START_OF_TIME, now, tomorrow),
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");

    Tld registry = Tld.get("xn--q9jyb4c");
    assertThat(registry.getEapFeeFor(now.minusHours(1)).getCost())
        .isEqualTo(BigDecimal.ZERO.setScale(2, ROUND_UNNECESSARY));
    assertThat(registry.getEapFeeFor(now.plusHours(1)).getCost())
        .isEqualTo(new BigDecimal("50.00"));
    assertThat(registry.getEapFeeFor(now.plusDays(1).plusHours(1)).getCost())
        .isEqualTo(new BigDecimal("10.00"));
  }

  @Test
  void testSuccess_addGracePeriodFlag() throws Exception {
    runCommandForced(
        "--add_grace_period=PT300S",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getAddGracePeriodLength()).isEqualTo(standardMinutes(5));
  }

  @Test
  void testSuccess_roidSuffixWorks() throws Exception {
    runCommandForced("--roid_suffix=RSUFFIX", "--dns_writers=VoidDnsWriter", "tld");
    assertThat(Tld.get("tld").getRoidSuffix()).isEqualTo("RSUFFIX");
  }

  @Test
  void testSuccess_escrow() throws Exception {
    runCommandForced(
        "--escrow=true", "--roid_suffix=Q9JYB4C", "--dns_writers=VoidDnsWriter", "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getEscrowEnabled()).isTrue();
  }

  @Test
  void testSuccess_noEscrow() throws Exception {
    runCommandForced(
        "--escrow=false", "--roid_suffix=Q9JYB4C", "--dns_writers=VoidDnsWriter", "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getEscrowEnabled()).isFalse();
  }

  @Test
  void testSuccess_redemptionGracePeriodFlag() throws Exception {
    runCommandForced(
        "--redemption_grace_period=PT300S",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getRedemptionGracePeriodLength())
        .isEqualTo(standardMinutes(5));
  }

  @Test
  void testSuccess_pendingDeleteLengthFlag() throws Exception {
    runCommandForced(
        "--pending_delete_length=PT300S",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getPendingDeleteLength()).isEqualTo(standardMinutes(5));
  }

  @Test
  void testSuccess_automaticTransferLengthFlag() throws Exception {
    runCommandForced(
        "--automatic_transfer_length=PT300S",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getAutomaticTransferLength()).isEqualTo(standardMinutes(5));
  }

  @Test
  void testSuccess_createBillingCostFlag() throws Exception {
    runCommandForced(
        "--create_billing_cost=\"USD 42.42\"",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getCreateBillingCost()).isEqualTo(Money.of(USD, 42.42));
  }

  @Test
  void testSuccess_restoreBillingCostFlag() throws Exception {
    runCommandForced(
        "--restore_billing_cost=\"USD 42.42\"",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getRestoreBillingCost()).isEqualTo(Money.of(USD, 42.42));
  }

  @Test
  void testSuccess_serverStatusChangeCostFlag() throws Exception {
    runCommandForced(
        "--server_status_change_cost=\"USD 42.42\"",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getServerStatusChangeBillingCost())
        .isEqualTo(Money.of(USD, 42.42));
  }

  @Test
  void testSuccess_registryLockOrUnlockCostFlag() throws Exception {
    runCommandForced(
        "--registry_lock_or_unlock_cost=\"USD 42.42\"",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getRegistryLockOrUnlockBillingCost())
        .isEqualTo(Money.of(USD, 42.42));
  }

  @Test
  void testSuccess_nonUsdBillingCostFlag() throws Exception {
    runCommandForced(
        "--create_billing_cost=\"JPY 12345\"",
        "--restore_billing_cost=\"JPY 67890\"",
        "--initial_renew_billing_cost=\"JPY 101112\"",
        "--server_status_change_cost=\"JPY 97865\"",
        "--registry_lock_or_unlock_cost=\"JPY 9001\"",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    Tld registry = Tld.get("xn--q9jyb4c");
    assertThat(registry.getCreateBillingCost()).isEqualTo(Money.ofMajor(JPY, 12345));
    assertThat(registry.getRestoreBillingCost()).isEqualTo(Money.ofMajor(JPY, 67890));
    assertThat(registry.getStandardRenewCost(START_OF_TIME)).isEqualTo(Money.ofMajor(JPY, 101112));
  }

  @Test
  void testSuccess_multipartTld() throws Exception {
    runCommandForced("co.uk", "--roid_suffix=COUK", "--dns_writers=VoidDnsWriter");

    Tld registry = Tld.get("co.uk");
    assertThat(registry.getTldState(new DateTime())).isEqualTo(PREDELEGATION);
    assertThat(registry.getAddGracePeriodLength()).isEqualTo(Tld.DEFAULT_ADD_GRACE_PERIOD);
    assertThat(registry.getRedemptionGracePeriodLength())
        .isEqualTo(Tld.DEFAULT_REDEMPTION_GRACE_PERIOD);
    assertThat(registry.getPendingDeleteLength()).isEqualTo(Tld.DEFAULT_PENDING_DELETE_LENGTH);
  }

  @Test
  void testSuccess_setReservedLists() throws Exception {
    runCommandForced(
        "--reserved_lists=xn--q9jyb4c_abuse,common_abuse",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getReservedListNames())
        .containsExactly("xn--q9jyb4c_abuse", "common_abuse");
  }

  @Test
  void testFailure_invalidAddGracePeriod() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--add_grace_period=5m",
                    "--roid_suffix=Q9JYB4C",
                    "--dns_writers=VoidDnsWriter",
                    "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("Invalid format: \"5m\"");
  }

  @Test
  void testFailure_invalidRedemptionGracePeriod() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--redemption_grace_period=5m",
                    "--roid_suffix=Q9JYB4C",
                    "--dns_writers=VoidDnsWriter",
                    "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("Invalid format: \"5m\"");
  }

  @Test
  void testFailure_invalidPendingDeleteLength() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--pending_delete_length=5m",
                    "--roid_suffix=Q9JYB4C",
                    "--dns_writers=VoidDnsWriter",
                    "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("Invalid format: \"5m\"");
  }

  @Test
  void testFailure_invalidTldState() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--initial_tld_state=INVALID_STATE",
                    "--roid_suffix=Q9JYB4C",
                    "--dns_writers=VoidDnsWriter",
                    "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("Invalid value for --initial_tld_state parameter");
  }

  @Test
  void testFailure_bothTldStateFlags() {
    DateTime now = DateTime.now(UTC);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    String.format(
                        "--tld_state_transitions=%s=PREDELEGATION,%s=START_DATE_SUNRISE",
                        now, now.plus(Duration.millis(1))),
                    "--initial_tld_state=GENERAL_AVAILABILITY",
                    "--dns_writers=VoidDnsWriter",
                    "xn--q9jyb4c"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Don't pass both --initial_tld_state and --tld_state_transitions");
  }

  @Test
  void testFailure_negativeInitialRenewBillingCost() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--initial_renew_billing_cost=USD -42",
                    "--roid_suffix=Q9JYB4C",
                    "--dns_writers=VoidDnsWriter",
                    "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("Renew billing cost cannot be negative");
  }

  @Test
  void testFailure_invalidEapCurrency() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    String.format("--eap_fee_schedule=\"%s=JPY 123456\"", START_OF_TIME),
                    "--roid_suffix=Q9JYB4C",
                    "--dns_writers=VoidDnsWriter",
                    "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("All EAP fees must be in the TLD's currency");
  }

  @Test
  void testFailure_noTldName() {
    ParameterException thrown = assertThrows(ParameterException.class, this::runCommandForced);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Main parameters are required (\"Names of the TLDs\")");
  }

  @Test
  void testFailure_noDnsWriter() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("xn--q9jyb4c", "--roid_suffix=Q9JYB4C"));
    assertThat(thrown).hasMessageThat().contains("At least one DNS writer must be specified");
  }

  @Test
  void testFailure_alreadyExists() {
    createTld("xn--q9jyb4c");
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                runCommandForced(
                    "--roid_suffix=NOTDUPE", "--dns_writers=VoidDnsWriter", "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("TLD 'xn--q9jyb4c' already exists");
  }

  @Test
  void testFailure_tldStartsWithDigit() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("1foo", "--roid_suffix=1FOO", "--dns_writers=VoidDnsWriter"));
    assertThat(thrown).hasMessageThat().contains("TLDs cannot begin with a number");
  }

  @Test
  void testSuccess_setAllowedRegistrants() throws Exception {
    runCommandForced(
        "--allowed_registrants=alice,bob",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getAllowedRegistrantContactIds())
        .containsExactly("alice", "bob");
  }

  @Test
  void testSuccess_emptyAllowedRegistrants() throws Exception {
    runCommandForced(
        "--allowed_registrants=",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getAllowedRegistrantContactIds()).isEmpty();
  }

  @Test
  void testSuccess_setAllowedNameservers() throws Exception {
    runCommandForced(
        "--allowed_nameservers=ns1.example.com,ns2.example.com",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=FooDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getAllowedFullyQualifiedHostNames())
        .containsExactly("ns1.example.com", "ns2.example.com");
  }

  @Test
  void testSuccess_emptyAllowedNameservers() throws Exception {
    runCommandForced(
        "--allowed_nameservers=",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=FooDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getAllowedFullyQualifiedHostNames()).isEmpty();
  }

  @Test
  void testSuccess_setCommonReservedListOnTld() throws Exception {
    runSuccessfulReservedListsTest("common_abuse");
  }

  @Test
  void testSuccess_setTldSpecificReservedListOnTld() throws Exception {
    runSuccessfulReservedListsTest("xn--q9jyb4c_abuse");
  }

  @Test
  void testSuccess_setCommonReservedListAndTldSpecificReservedListOnTld() throws Exception {
    runSuccessfulReservedListsTest("common_abuse,xn--q9jyb4c_abuse");
  }

  @Test
  void testFailure_setReservedListFromOtherTld() {
    runFailureReservedListsTest(
        "tld_banned",
        IllegalArgumentException.class,
        "The reserved list(s) tld_banned cannot be applied to the tld xn--q9jyb4c");
  }

  @Test
  void testSuccess_setReservedListFromOtherTld_withOverride() throws Exception {
    runReservedListsTestOverride("tld_banned");
  }

  @Test
  void testFailure_setCommonAndReservedListFromOtherTld() {
    runFailureReservedListsTest(
        "common_abuse,tld_banned",
        IllegalArgumentException.class,
        "The reserved list(s) tld_banned cannot be applied to the tld xn--q9jyb4c");
  }

  @Test
  void testSuccess_setCommonAndReservedListFromOtherTld_withOverride() throws Exception {
    command.errorPrintStream = System.err;
    runReservedListsTestOverride("common_abuse,tld_banned");
    String errMsg =
        "Error overridden: The reserved list(s) tld_banned "
            + "cannot be applied to the tld xn--q9jyb4c";
    assertThat(getStderrAsString()).contains(errMsg);
  }

  @Test
  void testFailure_setMultipleReservedListsFromOtherTld() {
    runFailureReservedListsTest(
        "tld_banned,soy_expurgated",
        IllegalArgumentException.class,
        "The reserved list(s) tld_banned, soy_expurgated cannot be applied to the tld xn--q9jyb4c");
  }

  @Test
  void testSuccess_setMultipleReservedListsFromOtherTld_withOverride() throws Exception {
    runReservedListsTestOverride("tld_banned,soy_expurgated");
  }

  @Test
  void testFailure_setNonExistentReservedLists() {
    runFailureReservedListsTest(
        "xn--q9jyb4c_asdf,common_asdsdgh",
        IllegalArgumentException.class,
        "Could not find reserved list xn--q9jyb4c_asdf to add to the tld");
  }

  @Test
  void testSuccess_setPremiumList() throws Exception {
    runCommandForced(
        "--premium_list=xn--q9jyb4c",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=FooDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getPremiumListName()).hasValue("xn--q9jyb4c");
  }

  @Test
  void testSuccess_setDriveFolderIdToValue() throws Exception {
    runCommandForced(
        "--drive_folder_id=madmax2030",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getDriveFolderId()).isEqualTo("madmax2030");
  }

  @Test
  void testSuccess_setDriveFolderIdToNull() throws Exception {
    runCommandForced(
        "--drive_folder_id=null",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getDriveFolderId()).isNull();
  }

  @Test
  void testSuccess_setsIdnTables() throws Exception {
    runCommandForced(
        "--idn_tables=extended_latin,ja",
        "--roid_suffix=ASDF",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getIdnTables())
        .containsExactly(IdnTableEnum.EXTENDED_LATIN, IdnTableEnum.JA);
  }

  @Test
  void testFailure_invalidIdnTable() throws Exception {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--idn_tables=extended_latin,bad_value",
                    "--roid_suffix=ASDF",
                    "--dns_writers=VoidDnsWriter",
                    "xn--q9jyb4c"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "IDN tables [EXTENDED_LATIN, BAD_VALUE] contained invalid value(s). Possible values:"
                + " [EXTENDED_LATIN, UNCONFUSABLE_LATIN, JA]");
  }

  @Test
  void testFailure_setPremiumListThatDoesntExist() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--premium_list=phonies",
                    "--roid_suffix=Q9JYB4C",
                    "--dns_writers=VoidDnsWriter",
                    "xn--q9jyb4c"));
    assertThat(thrown).hasMessageThat().contains("The premium list 'phonies' doesn't exist");
  }

  @Test
  void testFailure_specifiedDnsWriters_dontExist() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "xn--q9jyb4c", "--roid_suffix=Q9JYB4C", "--dns_writers=Invalid,Deadbeef"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Invalid DNS writer name(s) specified: [Invalid, Deadbeef]");
  }

  @Test
  void testSuccess_defaultToken() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken()
                .asBuilder()
                .setToken("abc123")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedTlds(ImmutableSet.of("xn--q9jyb4c"))
                .build());
    runCommandForced(
        "--default_tokens=abc123",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=FooDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getDefaultPromoTokens()).containsExactly(token.createVKey());
  }

  @Test
  void testSuccess_multipleDefaultTokens() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken()
                .asBuilder()
                .setToken("abc123")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedTlds(ImmutableSet.of("xn--q9jyb4c"))
                .build());
    AllocationToken token2 =
        persistResource(
            new AllocationToken()
                .asBuilder()
                .setToken("token")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedTlds(ImmutableSet.of("xn--q9jyb4c"))
                .build());
    runCommandForced(
        "--default_tokens=abc123,token",
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=FooDnsWriter",
        "xn--q9jyb4c");
    assertThat(Tld.get("xn--q9jyb4c").getDefaultPromoTokens())
        .containsExactly(token.createVKey(), token2.createVKey());
  }

  @Test
  void testFailure_specifiedDefaultToken_doesntExist() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                runCommandForced(
                    "xn--q9jyb4c",
                    "--default_tokens=InvalidToken",
                    "--roid_suffix=Q9JYB4C",
                    "--dns_writers=FooDnsWriter"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Tokens with keys [VKey<AllocationToken>(sql:InvalidToken)] did not exist");
  }

  private void runSuccessfulReservedListsTest(String reservedLists) throws Exception {
    runCommandForced(
        "--reserved_lists",
        reservedLists,
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
  }

  private void runReservedListsTestOverride(String reservedLists) throws Exception {
    runCommandForced(
        "--override_reserved_list_rules",
        "--reserved_lists",
        reservedLists,
        "--roid_suffix=Q9JYB4C",
        "--dns_writers=VoidDnsWriter",
        "xn--q9jyb4c");
  }

  private void runFailureReservedListsTest(
      String reservedLists, Class<? extends Exception> errorClass, String errorMsg) {
    Exception e =
        assertThrows(
            errorClass,
            () ->
                runCommandForced(
                    "--reserved_lists",
                    reservedLists,
                    "--roid_suffix=Q9JYB4C",
                    "--dns_writers=VoidDnsWriter",
                    "xn--q9jyb4c"));
    assertThat(e).hasMessageThat().isEqualTo(errorMsg);
  }
}
