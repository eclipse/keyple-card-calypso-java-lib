/* **************************************************************************************
 * Copyright (c) 2023 Calypso Networks Association https://calypsonet.org/
 *
 * See the NOTICE file(s) distributed with this work for additional information
 * regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ************************************************************************************** */
package org.eclipse.keyple.card.calypso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.keyple.core.util.HexUtil;
import org.eclipse.keypop.calypso.card.GetDataTag;
import org.eclipse.keypop.calypso.card.SelectFileControl;
import org.eclipse.keypop.calypso.card.card.CalypsoCard;
import org.eclipse.keypop.calypso.card.card.ElementaryFile;
import org.eclipse.keypop.calypso.card.card.FileHeader;
import org.eclipse.keypop.calypso.card.transaction.FreeTransactionManager;
import org.eclipse.keypop.calypso.card.transaction.SearchCommandData;
import org.eclipse.keypop.card.ChannelControl;
import org.eclipse.keypop.card.spi.CardRequestSpi;
import org.junit.Before;
import org.junit.Test;

public class FreeTransactionManagerAdapterTest extends AbstractTransactionManager {

  private FreeTransactionManager cardTransactionManager;

  @Override
  void initTransactionManager() {
    cardTransactionManager =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createFreeTransactionManager(cardReader, calypsoCard);
  }

  @Before
  public void setUp() throws Exception {
    cardReader = mock(ReaderMock.class);
    initCalypsoCardAndTransactionManager(SELECT_APPLICATION_RESPONSE_PRIME_REVISION_3);
  }

  @Test
  public void
      prepareSelectFile_whenLidIs1234AndCardIsPrimeRevision3_shouldPrepareSelectFileApduWith1234()
          throws Exception {
    short lid = 0x1234;
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(CARD_SELECT_FILE_1234_CMD, CARD_SELECT_FILE_1234_RSP);
    cardTransactionManager.prepareSelectFile(lid);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);
    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));
  }

  @Test
  public void
      prepareSelectFile_whenLidIs1234AndCardIsPrimeRevision2_shouldPrepareSelectFileApduWith1234()
          throws Exception {
    short lid = 0x1234;
    initCalypsoCardAndTransactionManager(SELECT_APPLICATION_RESPONSE_PRIME_REVISION_2);
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_SELECT_FILE_1234_CMD_PRIME_REV2, CARD_SELECT_FILE_1234_RSP_PRIME_REV2);
    cardTransactionManager.prepareSelectFile(lid);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);
    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));
  }

  @Test
  public void
      prepareSelectFile_whenSelectFileControlIsFirstEF_shouldPrepareSelectFileApduWithP2_02_P1_00()
          throws Exception {
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(CARD_SELECT_FILE_FIRST_CMD, CARD_SELECT_FILE_1234_RSP);
    cardTransactionManager.prepareSelectFile(SelectFileControl.FIRST_EF);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);
    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));
  }

  @Test
  public void
      prepareSelectFile_whenSelectFileControlIsNextEF_shouldPrepareSelectFileApduWithP2_02_P1_02()
          throws Exception {
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(CARD_SELECT_FILE_NEXT_CMD, CARD_SELECT_FILE_1234_RSP);
    cardTransactionManager.prepareSelectFile(SelectFileControl.NEXT_EF);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);
    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));
  }

  @Test
  public void
      prepareSelectFile_whenSelectFileControlIsCurrentEF_shouldPrepareSelectFileApduWithP2_09_P1_00()
          throws Exception {
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(CARD_SELECT_FILE_CURRENT_CMD, CARD_SELECT_FILE_1234_RSP);
    cardTransactionManager.prepareSelectFile(SelectFileControl.CURRENT_DF);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);
    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareGetData_whenGetDataTagIsNull_shouldThrowIAE() {
    cardTransactionManager.prepareGetData(null);
  }

  @Test
  public void prepareGetData_whenGetDataTagIsFCP_shouldPrepareSelectFileApduWithTagFCP()
      throws Exception {
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(CARD_GET_DATA_FCP_CMD, CARD_GET_DATA_FCP_RSP);
    cardTransactionManager.prepareGetData(GetDataTag.FCP_FOR_CURRENT_FILE);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);
    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));
  }

  @Test
  public void prepareGetData_whenGetDataTagIsEF_LIST_shouldPopulateCalypsoCard() throws Exception {
    // EF LIST
    // C028
    // C106 2001 07 02 1D 01
    // C106 20FF 09 01 1D 04
    // C106 F123 10 04 F3 F4
    // C106 F124 11 08 F3 F4
    // C106 F125 1F 09 F3 F4
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(CARD_GET_DATA_EF_LIST_CMD, CARD_GET_DATA_EF_LIST_RSP);

    assertThat(calypsoCard.getFiles()).isEmpty();

    cardTransactionManager.prepareGetData(GetDataTag.EF_LIST);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);
    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFiles()).hasSize(5);

    FileHeader fileHeader07 = calypsoCard.getFileBySfi((byte) 0x07).getHeader();
    assertThat(fileHeader07.getLid()).isEqualTo((short) 0x2001);
    assertThat(fileHeader07.getEfType()).isEqualTo(ElementaryFile.Type.LINEAR);
    assertThat(fileHeader07.getRecordSize()).isEqualTo(0x1D);
    assertThat(fileHeader07.getRecordsNumber()).isEqualTo(0x01);

    FileHeader fileHeader09 = calypsoCard.getFileBySfi((byte) 0x09).getHeader();
    assertThat(fileHeader09.getLid()).isEqualTo((short) 0x20FF);
    assertThat(fileHeader09.getEfType()).isEqualTo(ElementaryFile.Type.BINARY);
    assertThat(fileHeader09.getRecordSize()).isEqualTo(0x1D);
    assertThat(fileHeader09.getRecordsNumber()).isEqualTo(0x04);

    FileHeader fileHeader10 = calypsoCard.getFileBySfi((byte) 0x10).getHeader();
    assertThat(fileHeader10.getLid()).isEqualTo((short) 0xF123);
    assertThat(fileHeader10.getEfType()).isEqualTo(ElementaryFile.Type.CYCLIC);
    assertThat(fileHeader10.getRecordSize()).isEqualTo((byte) 0xF3);
    assertThat(fileHeader10.getRecordsNumber()).isEqualTo((byte) 0xF4);

    FileHeader fileHeader11 = calypsoCard.getFileBySfi((byte) 0x11).getHeader();
    assertThat(fileHeader11.getLid()).isEqualTo((short) 0xF124);
    assertThat(fileHeader11.getEfType()).isEqualTo(ElementaryFile.Type.SIMULATED_COUNTERS);
    assertThat(fileHeader11.getRecordSize()).isEqualTo((byte) 0xF3);
    assertThat(fileHeader11.getRecordsNumber()).isEqualTo((byte) 0xF4);

    FileHeader fileHeader1F = calypsoCard.getFileBySfi((byte) 0x1F).getHeader();
    assertThat(fileHeader1F.getLid()).isEqualTo((short) 0xF125);
    assertThat(fileHeader1F.getEfType()).isEqualTo(ElementaryFile.Type.COUNTERS);
    assertThat(fileHeader1F.getRecordSize()).isEqualTo((byte) 0xF3);
    assertThat(fileHeader1F.getRecordsNumber()).isEqualTo((byte) 0xF4);

    assertThat(calypsoCard.getFileByLid((short) 0x20FF))
        .isEqualTo(calypsoCard.getFileBySfi((byte) 0x09));
  }

  @Test
  public void prepareGetData_whenGetDataTagIsTRACEABILITY_INFORMATION_shouldPopulateCalypsoCard()
      throws Exception {
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_GET_DATA_TRACEABILITY_INFORMATION_CMD, CARD_GET_DATA_TRACEABILITY_INFORMATION_RSP);

    cardTransactionManager.prepareGetData(GetDataTag.TRACEABILITY_INFORMATION);

    assertThat(calypsoCard.getTraceabilityInformation()).isEmpty();

    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);
    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getTraceabilityInformation())
        .isEqualTo(HexUtil.toByteArray("00112233445566778899"));
  }

  @Test
  public void prepareGetData_whenGetDataTagIsFCI_shouldPrepareSelectFileApduWithTagFCI()
      throws Exception {
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(CARD_GET_DATA_FCI_CMD, CARD_GET_DATA_FCI_RSP);
    cardTransactionManager.prepareGetData(GetDataTag.FCI_FOR_CURRENT_DF);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);
    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadRecord_whenSfiIsGreaterThan30_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecord((byte) 31, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadRecord_whenRecordNumberIsLessThan0_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecord(FILE7, -1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadRecord_whenRecordNumberIsMoreThan250_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecord(FILE7, 251);
  }

  @Test
  public void prepareReadRecord_whenSfi07RecNumber1_shouldPrepareReadRecordApduWithSfi07RecNumber1()
      throws Exception {
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(CARD_READ_REC_SFI7_REC1_CMD, CARD_READ_REC_SFI7_REC1_RSP);
    cardTransactionManager.prepareReadRecord(FILE7, 1);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);
    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadRecords_whenSfiIsGreaterThan30_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecords((byte) 31, 1, 1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadRecords_whenFromRecordNumberIs0_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecords(FILE7, 0, 1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadRecords_whenFromRecordNumberIsGreaterThan250_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecords(FILE7, 251, 251, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadRecords_whenToRecordNumberIsLessThanFromRecordNumber_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecords(FILE7, 2, 1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadRecords_whenToRecordNumberIsGreaterThan250_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecords(FILE7, 1, 251, 1);
  }

  @Test
  public void
      prepareReadRecords_whenNbRecordsToReadMultipliedByRecSize2IsLessThanPayLoad_shouldPrepareOneCommand()
          throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(CARD_READ_RECORDS_FROM1_TO2_CMD, CARD_READ_RECORDS_FROM1_TO2_RSP);
    when(calypsoCard.getPayloadCapacity()).thenReturn(7);
    initTransactionManager();

    cardTransactionManager.prepareReadRecords((byte) 1, 1, 2, 1);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent(1))
        .isEqualTo(HexUtil.toByteArray("11"));
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent(2))
        .isEqualTo(HexUtil.toByteArray("22"));
  }

  @Test
  public void
      prepareReadRecords_whenNbRecordsToReadMultipliedByRecSize2IsGreaterThanPayLoad_shouldPrepareMultipleCommands()
          throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_READ_RECORDS_FROM1_TO2_CMD, CARD_READ_RECORDS_FROM1_TO2_RSP,
            CARD_READ_RECORDS_FROM3_TO4_CMD, CARD_READ_RECORDS_FROM3_TO4_RSP,
            CARD_READ_RECORDS_FROM5_TO5_CMD, CARD_READ_RECORDS_FROM5_TO5_RSP);
    when(calypsoCard.getPayloadCapacity()).thenReturn(7);
    initTransactionManager();

    cardTransactionManager.prepareReadRecords((byte) 1, 1, 5, 1);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent(1))
        .isEqualTo(HexUtil.toByteArray("11"));
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent(2))
        .isEqualTo(HexUtil.toByteArray("22"));
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent(3))
        .isEqualTo(HexUtil.toByteArray("33"));
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent(4))
        .isEqualTo(HexUtil.toByteArray("44"));
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent(5))
        .isEqualTo(HexUtil.toByteArray("55"));
  }

  @Test(expected = UnsupportedOperationException.class)
  public void prepareReadRecordsPartially_whenProductTypeIsNotPrimeRev3OrLight_shouldThrowUOE()
      throws Exception {
    initCalypsoCardAndTransactionManager(SELECT_APPLICATION_RESPONSE_PRIME_REVISION_2);
    cardTransactionManager.prepareReadRecordsPartially((byte) 1, 1, 1, 1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadRecordsPartially_whenSfiIsNegative_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecordsPartially((byte) -1, 1, 1, 1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadRecordsPartially_whenSfiIsGreaterThan30_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecordsPartially((byte) 31, 1, 1, 1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadRecordsPartially_whenFromRecordNumberIsZero_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecordsPartially((byte) 1, 0, 1, 1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadRecordsPartially_whenFromRecordNumberGreaterThan250_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecordsPartially((byte) 1, 251, 251, 1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void
      prepareReadRecordsPartially_whenToRecordNumberLessThanFromRecordNumber_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecordsPartially((byte) 1, 2, 1, 1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void
      prepareReadRecordsPartially_whenToRecordNumberGreaterThan250MinusFromRecordNumber_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecordsPartially((byte) 1, 1, 251, 1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadRecordsPartially_whenOffsetIsNegative_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecordsPartially((byte) 1, 1, 1, -1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadRecordsPartially_whenOffsetGreaterThan249_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecordsPartially((byte) 1, 1, 1, 250, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadRecordsPartially_whenNbBytesToReadIsZero_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecordsPartially((byte) 1, 1, 1, 1, 0);
  }

  @Test(expected = IllegalArgumentException.class)
  public void
      prepareReadRecordsPartially_whenNbBytesToReadIsGreaterThan250MinusOffset_shouldThrowIAE() {
    cardTransactionManager.prepareReadRecordsPartially((byte) 1, 1, 1, 3, 248);
  }

  @Test
  public void
      prepareReadRecordsPartially_whenNbRecordsToReadMultipliedByNbBytesToReadIsLessThanPayLoad_shouldPrepareOneCommand()
          throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_READ_RECORD_MULTIPLE_REC1_OFFSET3_NB_BYTE1_CMD,
            CARD_READ_RECORD_MULTIPLE_REC1_OFFSET3_NB_BYTE1_RSP);
    when(calypsoCard.getPayloadCapacity()).thenReturn(3);
    initTransactionManager();

    cardTransactionManager.prepareReadRecordsPartially((byte) 1, 1, 2, 3, 1);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent(1))
        .isEqualTo(HexUtil.toByteArray("00000011"));
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent(2))
        .isEqualTo(HexUtil.toByteArray("00000022"));
  }

  @Test
  public void
      prepareReadRecordsPartially_whenNbRecordsToReadMultipliedByNbBytesToReadIsGreaterThanPayLoad_shouldPrepareMultipleCommands()
          throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_READ_RECORD_MULTIPLE_REC1_OFFSET3_NB_BYTE1_CMD,
            CARD_READ_RECORD_MULTIPLE_REC1_OFFSET3_NB_BYTE1_RSP,
            CARD_READ_RECORD_MULTIPLE_REC3_OFFSET3_NB_BYTE1_CMD,
            CARD_READ_RECORD_MULTIPLE_REC3_OFFSET3_NB_BYTE1_RSP,
            CARD_READ_RECORD_MULTIPLE_REC5_OFFSET3_NB_BYTE1_CMD,
            CARD_READ_RECORD_MULTIPLE_REC5_OFFSET3_NB_BYTE1_RSP);
    when(calypsoCard.getPayloadCapacity()).thenReturn(2);
    initTransactionManager();

    cardTransactionManager.prepareReadRecordsPartially((byte) 1, 1, 5, 3, 1);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent(1))
        .isEqualTo(HexUtil.toByteArray("00000011"));
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent(2))
        .isEqualTo(HexUtil.toByteArray("00000022"));
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent(3))
        .isEqualTo(HexUtil.toByteArray("00000033"));
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent(4))
        .isEqualTo(HexUtil.toByteArray("00000044"));
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent(5))
        .isEqualTo(HexUtil.toByteArray("00000055"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadBinary_whenSfiIsNegative_shouldThrowIAE() {
    cardTransactionManager.prepareReadBinary((byte) -1, 1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadBinary_whenSfiIsGreaterThan30_shouldThrowIAE() {
    cardTransactionManager.prepareReadBinary((byte) 31, 1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadBinary_whenOffsetIsNegative_shouldThrowIAE() {
    cardTransactionManager.prepareReadBinary((byte) 1, -1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadBinary_whenOffsetIsGreaterThan32767_shouldThrowIAE() {
    cardTransactionManager.prepareReadBinary((byte) 1, 32768, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadBinary_whenNbBytesToReadIsLessThan1_shouldThrowIAE() {
    cardTransactionManager.prepareReadBinary((byte) 1, 1, 0);
  }

  @Test
  public void
      prepareReadBinary_whenSfiIsNot0AndOffsetIsGreaterThan255_shouldAddFirstAReadBinaryCommand()
          throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_READ_BINARY_SFI1_OFFSET0_1B_CMD,
            CARD_READ_BINARY_SFI1_OFFSET0_1B_RSP,
            CARD_READ_BINARY_SFI0_OFFSET256_1B_CMD,
            CARD_READ_BINARY_SFI0_OFFSET256_1B_RSP);

    cardTransactionManager.prepareReadBinary((byte) 1, 256, 1);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent())
        .startsWith(HexUtil.toByteArray("1100"))
        .endsWith(HexUtil.toByteArray("0066"))
        .hasSize(257);
  }

  @Test
  public void prepareReadBinary_whenNbBytesToReadIsLessThanPayLoad_shouldPrepareOneCommand()
      throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_READ_BINARY_SFI1_OFFSET0_1B_CMD, CARD_READ_BINARY_SFI1_OFFSET0_1B_RSP);
    when(calypsoCard.getPayloadCapacity()).thenReturn(2);
    initTransactionManager();

    cardTransactionManager.prepareReadBinary((byte) 1, 0, 1);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent())
        .isEqualTo(HexUtil.toByteArray("11"));
  }

  @Test
  public void
      prepareReadBinary_whenNbBytesToReadIsGreaterThanPayLoad_shouldPrepareMultipleCommands()
          throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_READ_BINARY_SFI1_OFFSET0_1B_CMD, CARD_READ_BINARY_SFI1_OFFSET0_1B_RSP);
    when(calypsoCard.getPayloadCapacity()).thenReturn(2);
    initTransactionManager();

    cardTransactionManager.prepareReadBinary((byte) 1, 0, 1);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent())
        .isEqualTo(HexUtil.toByteArray("11"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareReadCounter_whenSfiIsGreaterThan30_shouldThrowIAE() {
    cardTransactionManager.prepareReadCounter((byte) 31, 1);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void prepareSearchRecords_whenProductTypeIsNotPrimeRev3_shouldThrowUOE() throws Exception {
    initCalypsoCardAndTransactionManager(SELECT_APPLICATION_RESPONSE_PRIME_REVISION_2);
    cardTransactionManager.prepareSearchRecords(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareSearchRecords_whenDataIsNull_shouldThrowIAE() {
    cardTransactionManager.prepareSearchRecords(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareSearchRecords_whenDataIsNotInstanceOfInternalAdapter_shouldThrowIAE() {
    cardTransactionManager.prepareSearchRecords(
        new SearchCommandData() {
          @Override
          public SearchCommandData setSfi(byte sfi) {
            return null;
          }

          @Override
          public SearchCommandData startAtRecord(int recordNumber) {
            return null;
          }

          @Override
          public SearchCommandData setOffset(int offset) {
            return null;
          }

          @Override
          public SearchCommandData enableRepeatedOffset() {
            return null;
          }

          @Override
          public SearchCommandData setSearchData(byte[] data) {
            return null;
          }

          @Override
          public SearchCommandData setMask(byte[] mask) {
            return null;
          }

          @Override
          public SearchCommandData fetchFirstMatchingResult() {
            return null;
          }

          @Override
          public List<Integer> getMatchingRecordNumbers() {
            return null;
          }
        });
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareSearchRecords_whenSfiIsNegative_shouldThrowIAE() {
    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .setSfi((byte) -1)
            .setSearchData(new byte[1]);
    cardTransactionManager.prepareSearchRecords(data);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareSearchRecords_whenSfiGreaterThanSfiMax_shouldThrowIAE() {
    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .setSfi((byte) 31)
            .setSearchData(new byte[1]);
    cardTransactionManager.prepareSearchRecords(data);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareSearchRecords_whenRecordNumberIs0_shouldThrowIAE() {
    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .startAtRecord(0)
            .setSearchData(new byte[1]);
    cardTransactionManager.prepareSearchRecords(data);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareSearchRecords_whenRecordNumberIsGreaterThan250_shouldThrowIAE() {
    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .startAtRecord(251)
            .setSearchData(new byte[1]);
    cardTransactionManager.prepareSearchRecords(data);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareSearchRecords_whenOffsetIsNegative_shouldThrowIAE() {
    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .setOffset(-1)
            .setSearchData(new byte[1]);
    cardTransactionManager.prepareSearchRecords(data);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareSearchRecords_whenOffsetIsGreaterThan249_shouldThrowIAE() {
    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .setOffset(250)
            .setSearchData(new byte[1]);
    cardTransactionManager.prepareSearchRecords(data);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareSearchRecords_whenSearchDataIsNotSet_shouldThrowIAE() {
    SearchCommandData data =
        CalypsoExtensionService.getInstance().getCalypsoCardApiFactory().createSearchCommandData();
    cardTransactionManager.prepareSearchRecords(data);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareSearchRecords_whenSearchDataIsNull_shouldThrowIAE() {
    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .setSearchData(null);
    cardTransactionManager.prepareSearchRecords(data);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareSearchRecords_whenSearchDataIsEmpty_shouldThrowIAE() {
    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .setSearchData(new byte[0]);
    cardTransactionManager.prepareSearchRecords(data);
  }

  @Test(expected = IllegalArgumentException.class)
  public void
      prepareSearchRecords_whenSearchDataLengthIsGreaterThan250MinusOffset0_shouldThrowIAE() {
    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .setSearchData(new byte[251]);
    cardTransactionManager.prepareSearchRecords(data);
  }

  @Test(expected = IllegalArgumentException.class)
  public void
      prepareSearchRecords_whenSearchDataLengthIsGreaterThan249MinusOffset1_shouldThrowIAE() {
    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .setOffset(1)
            .setSearchData(new byte[250]);
    cardTransactionManager.prepareSearchRecords(data);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareSearchRecords_whenMaskLengthIsGreaterThanSearchDataLength_shouldThrowIAE() {
    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .setSearchData(new byte[1])
            .setMask(new byte[2]);
    cardTransactionManager.prepareSearchRecords(data);
  }

  @Test
  public void prepareSearchRecords_whenUsingDefaultParameters_shouldPrepareDefaultCommand()
      throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_SEARCH_RECORD_MULTIPLE_SFI1_REC1_OFFSET0_AT_NO_FETCH_1234_FFFF_CMD,
            CARD_SEARCH_RECORD_MULTIPLE_SFI1_REC1_OFFSET0_AT_NO_FETCH_1234_FFFF_RSP);

    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .setSearchData(new byte[] {0x12, 0x34});
    cardTransactionManager.prepareSearchRecords(data);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(data.getMatchingRecordNumbers()).containsExactly(4, 6);
  }

  @Test
  public void prepareSearchRecords_whenSetAllParameters_shouldPrepareCustomCommand()
      throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_SEARCH_RECORD_MULTIPLE_SFI4_REC2_OFFSET3_FROM_FETCH_1234_FFFF_CMD,
            CARD_SEARCH_RECORD_MULTIPLE_SFI4_REC2_OFFSET3_FROM_FETCH_1234_FFFF_RSP);

    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .setSfi((byte) 4)
            .startAtRecord(2)
            .setOffset(3)
            .enableRepeatedOffset()
            .setSearchData(new byte[] {0x12, 0x34})
            .fetchFirstMatchingResult();
    cardTransactionManager.prepareSearchRecords(data);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(data.getMatchingRecordNumbers()).containsExactly(4, 6);
    assertThat(calypsoCard.getFileBySfi((byte) 4).getData().getContent(4))
        .isEqualTo(HexUtil.toByteArray("112233123456"));
  }

  @Test
  public void prepareSearchRecords_whenNoMask_shouldFillMaskWithFFh() throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_SEARCH_RECORD_MULTIPLE_SFI1_REC1_OFFSET0_AT_NO_FETCH_1234_FFFF_CMD,
            CARD_SEARCH_RECORD_MULTIPLE_SFI1_REC1_OFFSET0_AT_NO_FETCH_1234_FFFF_RSP);

    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .setSearchData(new byte[] {0x12, 0x34});
    cardTransactionManager.prepareSearchRecords(data);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(data.getMatchingRecordNumbers()).containsExactly(4, 6);
  }

  @Test
  public void prepareSearchRecords_whenPartialMask_shouldRightPadMaskWithFFh() throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_SEARCH_RECORD_MULTIPLE_SFI1_REC1_OFFSET0_AT_NO_FETCH_1234_56FF_CMD,
            CARD_SEARCH_RECORD_MULTIPLE_SFI1_REC1_OFFSET0_AT_NO_FETCH_1234_56FF_RSP);

    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .setSearchData(new byte[] {0x12, 0x34})
            .setMask(new byte[] {0x56});
    cardTransactionManager.prepareSearchRecords(data);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(data.getMatchingRecordNumbers()).containsExactly(4, 6);
  }

  @Test
  public void prepareSearchRecords_whenFullMask_shouldUseCompleteMask() throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_SEARCH_RECORD_MULTIPLE_SFI1_REC1_OFFSET0_AT_NO_FETCH_1234_5677_CMD,
            CARD_SEARCH_RECORD_MULTIPLE_SFI1_REC1_OFFSET0_AT_NO_FETCH_1234_5677_RSP);

    SearchCommandData data =
        CalypsoExtensionService.getInstance()
            .getCalypsoCardApiFactory()
            .createSearchCommandData()
            .setSearchData(new byte[] {0x12, 0x34})
            .setMask(new byte[] {0x56, 0x77});
    cardTransactionManager.prepareSearchRecords(data);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(data.getMatchingRecordNumbers()).containsExactly(4, 6);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void prepareCheckPinStatus_whenPinFeatureIsNotAvailable_shouldThrowISE() {
    cardTransactionManager.prepareCheckPinStatus();
  }

  @Test
  public void prepareCheckPinStatus_whenPinFeatureIsAvailable_shouldPrepareCheckPinStatusApdu()
      throws Exception {
    initCalypsoCardAndTransactionManager(SELECT_APPLICATION_RESPONSE_PRIME_REVISION_3_WITH_PIN);
    CardRequestSpi cardRequest = mockTransmitCardRequest(CARD_CHECK_PIN_CMD, SW_9000);
    cardTransactionManager.prepareCheckPinStatus();
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);
    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareAppendRecord_whenSfiIsGreaterThan30_shouldThrowIAE() {
    cardTransactionManager.prepareAppendRecord((byte) 31, new byte[3]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareAppendRecord_whenRecordDataIsNull_shouldThrowIAE() {
    cardTransactionManager.prepareAppendRecord(FILE7, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareUpdateRecord_whenSfiIsGreaterThan30_shouldThrowIAE() {
    cardTransactionManager.prepareUpdateRecord((byte) 31, 1, new byte[1]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareUpdateRecord_whenRecordNumberIsGreaterThan250_shouldThrowIAE() {
    cardTransactionManager.prepareUpdateRecord(FILE7, 251, new byte[1]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareUpdateRecord_whenRecordDataIsNull_shouldThrowIAE() {
    cardTransactionManager.prepareUpdateRecord(FILE7, 1, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareWriteRecord_whenSfiIsGreaterThan30_shouldThrowIAE() {
    cardTransactionManager.prepareWriteRecord((byte) 31, 1, new byte[1]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareWriteRecord_whenRecordNumberIsGreaterThan250_shouldThrowIAE() {
    cardTransactionManager.prepareWriteRecord(FILE7, 251, new byte[1]);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void prepareUpdateBinary_whenProductTypeIsNotPrimeRev2OrRev3_shouldThrowUOE()
      throws Exception {
    initCalypsoCardAndTransactionManager(SELECT_APPLICATION_RESPONSE_LIGHT);
    cardTransactionManager.prepareUpdateBinary((byte) 1, 1, new byte[1]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareUpdateBinary_whenSfiIsNegative_shouldThrowIAE() {
    cardTransactionManager.prepareUpdateBinary((byte) -1, 1, new byte[1]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareUpdateBinary_whenSfiIsGreaterThan30_shouldThrowIAE() {
    cardTransactionManager.prepareUpdateBinary((byte) 31, 1, new byte[1]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareUpdateBinary_whenOffsetIsNegative_shouldThrowIAE() {
    cardTransactionManager.prepareUpdateBinary((byte) 1, -1, new byte[1]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareUpdateBinary_whenOffsetIsGreaterThan32767_shouldThrowIAE() {
    cardTransactionManager.prepareUpdateBinary((byte) 1, 32768, new byte[1]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareUpdateBinary_whenDataIsNull_shouldThrowIAE() {
    cardTransactionManager.prepareUpdateBinary((byte) 1, 1, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareUpdateBinary_whenDataIsEmpty_shouldThrowIAE() {
    cardTransactionManager.prepareUpdateBinary((byte) 1, 1, new byte[0]);
  }

  @Test
  public void
      prepareUpdateBinary_whenSfiIsNot0AndOffsetIsGreaterThan255_shouldAddFirstAReadBinaryCommand()
          throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_READ_BINARY_SFI1_OFFSET0_1B_CMD,
            CARD_READ_BINARY_SFI1_OFFSET0_1B_RSP,
            CARD_UPDATE_BINARY_SFI0_OFFSET256_1B_CMD,
            SW_9000);

    cardTransactionManager.prepareUpdateBinary((byte) 1, 256, HexUtil.toByteArray("66"));
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));
  }

  @Test
  public void prepareUpdateBinary_whenDataLengthIsLessThanPayLoad_shouldPrepareOneCommand()
      throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(CARD_UPDATE_BINARY_SFI1_OFFSET4_1B_CMD, SW_9000);
    when(calypsoCard.getPayloadCapacity()).thenReturn(2);
    initTransactionManager();

    cardTransactionManager.prepareUpdateBinary((byte) 1, 4, HexUtil.toByteArray("55"));
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent())
        .isEqualTo(HexUtil.toByteArray("0000000055"));
  }

  @Test
  public void prepareUpdateBinary_whenDataLengthIsGreaterThanPayLoad_shouldPrepareMultipleCommands()
      throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_UPDATE_BINARY_SFI1_OFFSET0_2B_CMD, SW_9000,
            CARD_UPDATE_BINARY_SFI1_OFFSET2_2B_CMD, SW_9000,
            CARD_UPDATE_BINARY_SFI1_OFFSET4_1B_CMD, SW_9000);
    when(calypsoCard.getPayloadCapacity()).thenReturn(2);
    initTransactionManager();

    cardTransactionManager.prepareUpdateBinary((byte) 1, 0, HexUtil.toByteArray("1122334455"));
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent())
        .isEqualTo(HexUtil.toByteArray("1122334455"));
  }

  @Test(expected = UnsupportedOperationException.class)
  public void prepareWriteBinary_whenProductTypeIsNotPrimeRev2OrRev3_shouldThrowUOE()
      throws Exception {
    initCalypsoCardAndTransactionManager(SELECT_APPLICATION_RESPONSE_LIGHT);
    cardTransactionManager.prepareWriteBinary((byte) 1, 1, new byte[1]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareWriteBinary_whenSfiIsNegative_shouldThrowIAE() {
    cardTransactionManager.prepareWriteBinary((byte) -1, 1, new byte[1]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareWriteBinary_whenSfiIsGreaterThan30_shouldThrowIAE() {
    cardTransactionManager.prepareWriteBinary((byte) 31, 1, new byte[1]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareWriteBinary_whenOffsetIsNegative_shouldThrowIAE() {
    cardTransactionManager.prepareWriteBinary((byte) 1, -1, new byte[1]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareWriteBinary_whenOffsetIsGreaterThan32767_shouldThrowIAE() {
    cardTransactionManager.prepareWriteBinary((byte) 1, 32768, new byte[1]);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareWriteBinary_whenDataIsNull_shouldThrowIAE() {
    cardTransactionManager.prepareWriteBinary((byte) 1, 1, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareWriteBinary_whenDataIsEmpty_shouldThrowIAE() {
    cardTransactionManager.prepareWriteBinary((byte) 1, 1, new byte[0]);
  }

  @Test
  public void
      prepareWriteBinary_whenSfiIsNot0AndOffsetIsGreaterThan255_shouldAddFirstAReadBinaryCommand()
          throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_READ_BINARY_SFI1_OFFSET0_1B_CMD,
            CARD_READ_BINARY_SFI1_OFFSET0_1B_RSP,
            CARD_WRITE_BINARY_SFI0_OFFSET256_1B_CMD,
            SW_9000);

    cardTransactionManager.prepareWriteBinary((byte) 1, 256, HexUtil.toByteArray("66"));
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));
  }

  @Test
  public void prepareWriteBinary_whenDataLengthIsLessThanPayLoad_shouldPrepareOneCommand()
      throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(CARD_WRITE_BINARY_SFI1_OFFSET4_1B_CMD, SW_9000);
    when(calypsoCard.getPayloadCapacity()).thenReturn(2);
    initTransactionManager();

    cardTransactionManager.prepareWriteBinary((byte) 1, 4, HexUtil.toByteArray("55"));
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent())
        .isEqualTo(HexUtil.toByteArray("0000000055"));
  }

  @Test
  public void prepareWriteBinary_whenDataLengthIsGreaterThanPayLoad_shouldPrepareMultipleCommands()
      throws Exception {

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_WRITE_BINARY_SFI1_OFFSET0_2B_CMD, SW_9000,
            CARD_WRITE_BINARY_SFI1_OFFSET2_2B_CMD, SW_9000,
            CARD_WRITE_BINARY_SFI1_OFFSET4_1B_CMD, SW_9000);
    when(calypsoCard.getPayloadCapacity()).thenReturn(2);
    initTransactionManager();

    cardTransactionManager.prepareWriteBinary((byte) 1, 0, HexUtil.toByteArray("1122334455"));
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContent())
        .isEqualTo(HexUtil.toByteArray("1122334455"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareIncreaseCounter_whenSfiIsGreaterThan30_shouldThrowIAE() {
    cardTransactionManager.prepareIncreaseCounter((byte) 31, 1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareIncreaseCounter_whenValueIsLessThan0_shouldThrowIAE() {
    cardTransactionManager.prepareIncreaseCounter(FILE7, 1, -1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareIncreaseCounter_whenValueIsGreaterThan16777215_shouldThrowIAE() {
    cardTransactionManager.prepareIncreaseCounter(FILE7, 1, 16777216);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareIncreaseCounter_whenCounterNumberIsGreaterThan83_shouldThrowIAE() {
    cardTransactionManager.prepareIncreaseCounter(FILE7, 84, 1);
  }

  @Test
  public void prepareIncreaseCounter_whenParametersAreCorrect_shouldAddDecreaseCommand()
      throws Exception {
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_INCREASE_SFI11_CNT1_100U_CMD, CARD_INCREASE_SFI11_CNT1_8821U_RSP);

    cardTransactionManager.prepareIncreaseCounter((byte) 1, 1, 100);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContentAsCounterValue(1))
        .isEqualTo(8821);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareIncreaseCounter_whenCounterNumberIsLessThan0_shouldThrowIAE() {
    cardTransactionManager.prepareIncreaseCounter(FILE7, -1, 1);
  }

  @Test
  public void prepareIncreaseCounter_whenCounterNumberIs0_shouldNotThrowException() {
    FreeTransactionManager tm = cardTransactionManager.prepareIncreaseCounter(FILE7, 0, 1);
    assertThat(tm).isNotNull();
  }

  @Test
  public void prepareIncreaseCounters_whenCardIsLowerThanPrime3__shouldAddMultipleIncreaseCommands()
      throws Exception {
    when(calypsoCard.getProductType()).thenReturn(CalypsoCard.ProductType.BASIC);

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_INCREASE_SFI11_CNT1_100U_CMD, CARD_INCREASE_SFI11_CNT1_8821U_RSP);

    Map<Integer, Integer> counterNumberToIncValueMap = new HashMap<Integer, Integer>(1);
    counterNumberToIncValueMap.put(1, 100);

    cardTransactionManager.prepareIncreaseCounters((byte) 1, counterNumberToIncValueMap);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContentAsCounterValue(1))
        .isEqualTo(8821);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareIncreaseCounters_whenSfiIsGreaterThan30_shouldThrowIAE() {
    Map<Integer, Integer> counterNumberToIncValueMap = new HashMap<Integer, Integer>(1);
    counterNumberToIncValueMap.put(1, 1);
    cardTransactionManager.prepareIncreaseCounters((byte) 31, counterNumberToIncValueMap);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareIncreaseCounters_whenValueIsLessThan0_shouldThrowIAE() {
    Map<Integer, Integer> counterNumberToIncValueMap = new HashMap<Integer, Integer>(1);
    counterNumberToIncValueMap.put(1, -1);
    cardTransactionManager.prepareIncreaseCounters(FILE7, counterNumberToIncValueMap);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareIncreaseCounters_whenValueIsGreaterThan16777215_shouldThrowIAE() {
    Map<Integer, Integer> counterNumberToIncValueMap = new HashMap<Integer, Integer>(1);
    counterNumberToIncValueMap.put(84, 1);
    cardTransactionManager.prepareIncreaseCounters(FILE7, counterNumberToIncValueMap);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareIncreaseCounters_whenCounterNumberIsGreaterThan83_shouldThrowIAE() {
    Map<Integer, Integer> counterNumberToIncValueMap = new HashMap<Integer, Integer>(1);
    counterNumberToIncValueMap.put(1, 16777216);
    cardTransactionManager.prepareIncreaseCounters(FILE7, counterNumberToIncValueMap);
  }

  @Test
  public void prepareIncreaseCounters_whenParametersAreCorrect_shouldAddIncreaseMultipleCommand()
      throws Exception {
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_INCREASE_MULTIPLE_SFI1_C1_1_C2_2_C3_3_CMD,
            CARD_INCREASE_MULTIPLE_SFI1_C1_11_C2_22_C3_33_RSP);

    Map<Integer, Integer> counterNumberToIncValueMap = new HashMap<Integer, Integer>(3);
    counterNumberToIncValueMap.put(3, 3);
    counterNumberToIncValueMap.put(1, 1);
    counterNumberToIncValueMap.put(2, 2);
    cardTransactionManager.prepareIncreaseCounters((byte) 1, counterNumberToIncValueMap);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContentAsCounterValue(1))
        .isEqualTo(0x11);
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContentAsCounterValue(2))
        .isEqualTo(0x22);
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContentAsCounterValue(3))
        .isEqualTo(0x33);
  }

  @Test
  public void
      prepareIncreaseCounters_whenDataLengthIsGreaterThanPayLoad_shouldPrepareMultipleCommands()
          throws Exception {
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_INCREASE_MULTIPLE_SFI1_C1_1_C2_2_CMD,
            CARD_INCREASE_MULTIPLE_SFI1_C1_11_C2_22_RSP,
            CARD_INCREASE_MULTIPLE_SFI1_C3_3_CMD,
            CARD_INCREASE_MULTIPLE_SFI1_C3_33_RSP);
    when(calypsoCard.getPayloadCapacity()).thenReturn(9);
    initTransactionManager();

    Map<Integer, Integer> counterNumberToIncValueMap = new HashMap<Integer, Integer>(3);
    counterNumberToIncValueMap.put(1, 1);
    counterNumberToIncValueMap.put(2, 2);
    counterNumberToIncValueMap.put(3, 3);
    cardTransactionManager.prepareIncreaseCounters((byte) 1, counterNumberToIncValueMap);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContentAsCounterValue(1))
        .isEqualTo(0x11);
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContentAsCounterValue(2))
        .isEqualTo(0x22);
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContentAsCounterValue(3))
        .isEqualTo(0x33);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareDecreaseCounter_whenSfiIsGreaterThan30_shouldThrowIAE() {
    cardTransactionManager.prepareDecreaseCounter((byte) 31, 1, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareDecreaseCounter_whenValueIsLessThan0_shouldThrowIAE() {
    cardTransactionManager.prepareDecreaseCounter(FILE7, 1, -1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareDecreaseCounter_whenValueIsGreaterThan16777215_shouldThrowIAE() {
    cardTransactionManager.prepareDecreaseCounter(FILE7, 1, 16777216);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareDecreaseCounter_whenCounterNumberIsGreaterThan83_shouldThrowIAE() {
    cardTransactionManager.prepareDecreaseCounter(FILE7, 84, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareDecreaseCounter_whenCounterNumberIsLessThan0_shouldThrowIAE() {
    cardTransactionManager.prepareDecreaseCounter(FILE7, -1, 1);
  }

  @Test
  public void prepareDecreaseCounter_whenCounterNumberIs0_shouldNotThrowException() {
    FreeTransactionManager tm = cardTransactionManager.prepareDecreaseCounter(FILE7, 0, 1);
    assertThat(tm).isNotNull();
  }

  @Test
  public void prepareDecreaseCounter_whenParametersAreCorrect_shouldAddDecreaseMultipleCommand()
      throws Exception {
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_DECREASE_SFI10_CNT1_100U_CMD, CARD_DECREASE_SFI10_CNT1_4286U_RSP);

    cardTransactionManager.prepareDecreaseCounter((byte) 1, 1, 100);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContentAsCounterValue(1))
        .isEqualTo(4286);
  }

  @Test
  public void prepareDecreaseCounters_whenCardIsLowerThanPrime3_shouldThrowUOE() throws Exception {
    when(calypsoCard.getProductType()).thenReturn(CalypsoCard.ProductType.BASIC);

    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_DECREASE_SFI10_CNT1_100U_CMD, CARD_DECREASE_SFI10_CNT1_4286U_RSP);

    Map<Integer, Integer> counterNumberToDecValueMap = new HashMap<Integer, Integer>(1);
    counterNumberToDecValueMap.put(1, 100);

    cardTransactionManager.prepareDecreaseCounters((byte) 1, counterNumberToDecValueMap);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContentAsCounterValue(1))
        .isEqualTo(4286);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareDecreaseCounters_whenSfiIsGreaterThan30_shouldThrowIAE() {
    Map<Integer, Integer> counterNumberToIncValueMap = new HashMap<Integer, Integer>(1);
    counterNumberToIncValueMap.put(1, 1);
    cardTransactionManager.prepareDecreaseCounters((byte) 31, counterNumberToIncValueMap);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareDecreaseCounters_whenValueIsLessThan0_shouldThrowIAE() {
    Map<Integer, Integer> counterNumberToIncValueMap = new HashMap<Integer, Integer>(1);
    counterNumberToIncValueMap.put(1, -1);
    cardTransactionManager.prepareDecreaseCounters(FILE7, counterNumberToIncValueMap);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareDecreaseCounters_whenValueIsGreaterThan16777215_shouldThrowIAE() {
    Map<Integer, Integer> counterNumberToIncValueMap = new HashMap<Integer, Integer>(1);
    counterNumberToIncValueMap.put(84, 1);
    cardTransactionManager.prepareDecreaseCounters(FILE7, counterNumberToIncValueMap);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareDecreaseCounters_whenCounterNumberIsGreaterThan83_shouldThrowIAE() {
    Map<Integer, Integer> counterNumberToIncValueMap = new HashMap<Integer, Integer>(1);
    counterNumberToIncValueMap.put(1, 16777216);
    cardTransactionManager.prepareDecreaseCounters(FILE7, counterNumberToIncValueMap);
  }

  @Test
  public void prepareDecreaseCounters_whenParametersAreCorrect_shouldAddDecreaseMultipleCommand()
      throws Exception {
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_DECREASE_MULTIPLE_SFI1_C1_11_C2_22_C8_88_CMD,
            CARD_DECREASE_MULTIPLE_SFI1_C1_111_C2_222_C8_888_RSP);

    Map<Integer, Integer> counterNumberToIncValueMap = new HashMap<Integer, Integer>(3);
    counterNumberToIncValueMap.put(2, 0x22);
    counterNumberToIncValueMap.put(8, 0x88);
    counterNumberToIncValueMap.put(1, 0x11);
    cardTransactionManager.prepareDecreaseCounters((byte) 1, counterNumberToIncValueMap);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));

    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContentAsCounterValue(1))
        .isEqualTo(0x111);
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContentAsCounterValue(2))
        .isEqualTo(0x222);
    assertThat(calypsoCard.getFileBySfi((byte) 1).getData().getContentAsCounterValue(8))
        .isEqualTo(0x888);
  }

  @Test(expected = IllegalStateException.class)
  public void prepareSetCounter_whenCounterNotPreviouslyRead_shouldThrowISE() {
    cardTransactionManager.prepareSetCounter(FILE7, 1, 1);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void prepareSvReadAllLogs_whenPinFeatureIsNotAvailable_shouldThrowISE() {
    cardTransactionManager.prepareSvReadAllLogs();
  }

  @Test(expected = UnsupportedOperationException.class)
  public void prepareSvReadAllLogs_whenNotAnSVApplication_shouldThrowISE() throws Exception {
    initCalypsoCardAndTransactionManager(
        SELECT_APPLICATION_RESPONSE_PRIME_REVISION_3_WITH_STORED_VALUE);
    cardTransactionManager.prepareSvReadAllLogs();
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareVerifyPin_whenPINIsNull_shouldThrowIAE() {
    cardTransactionManager.prepareVerifyPin(null).processCommands(CHANNEL_CONTROL_KEEP_OPEN);
  }

  @Test(expected = IllegalArgumentException.class)
  public void prepareVerifyPin_whenPINIsNot4Digits_shouldThrowIAE() {
    cardTransactionManager
        .prepareVerifyPin(PIN_5_DIGITS.getBytes())
        .processCommands(CHANNEL_CONTROL_KEEP_OPEN);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void prepareVerifyPin_whenPINNotAvailable_shouldThrowUOE() {
    cardTransactionManager
        .prepareVerifyPin(PIN_OK.getBytes())
        .processCommands(CHANNEL_CONTROL_KEEP_OPEN);
  }

  @Test
  public void prepareVerifyPin_whenPINTransmittedInPlainText_shouldSendApduVerifyPIN()
      throws Exception {
    initCalypsoCardAndTransactionManager(SELECT_APPLICATION_RESPONSE_PRIME_REVISION_3_WITH_PIN);
    CardRequestSpi cardRequest = mockTransmitCardRequest(CARD_VERIFY_PIN_PLAIN_OK_CMD, SW_9000);
    cardTransactionManager
        .prepareVerifyPin(PIN_OK.getBytes())
        .processCommands(CHANNEL_CONTROL_KEEP_OPEN);
    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));
    verifyNoMoreInteractions(cardReader);
  }

  @Test
  public void prepareChangePin_whenTransmissionIsPlain_shouldSendApdusToTheCardAndTheSAM()
      throws Exception {
    initCalypsoCardAndTransactionManager(SELECT_APPLICATION_RESPONSE_PRIME_REVISION_3_WITH_PIN);

    calypsoCard.setPinAttemptRemaining(3);

    CardRequestSpi cardChangePinCardRequest =
        mockTransmitCardRequest(CARD_CHANGE_PIN_PLAIN_CMD, CARD_CHANGE_PIN_PLAIN_RSP);

    cardTransactionManager
        .prepareChangePin(NEW_PIN.getBytes())
        .processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardChangePinCardRequest)), any(ChannelControl.class));

    verifyNoMoreInteractions(cardReader);
  }

  @Test
  public void processCommands_whenOutOfSession_shouldExchangeApduWithCardOnly() throws Exception {
    CardRequestSpi cardRequest =
        mockTransmitCardRequest(
            CARD_READ_REC_SFI7_REC1_CMD,
            CARD_READ_REC_SFI7_REC1_RSP,
            CARD_READ_REC_SFI8_REC1_CMD,
            CARD_READ_REC_SFI8_REC1_RSP,
            CARD_READ_REC_SFI10_REC1_CMD,
            CARD_READ_REC_SFI10_REC1_RSP);

    cardTransactionManager.prepareReadRecord(FILE7, 1);
    cardTransactionManager.prepareReadRecord(FILE8, 1);
    cardTransactionManager.prepareReadRecord(FILE10, 1);
    cardTransactionManager.processCommands(CHANNEL_CONTROL_KEEP_OPEN);

    verify(cardReader)
        .transmitCardRequest(
            argThat(new CardRequestMatcher(cardRequest)), any(ChannelControl.class));
  }
}
