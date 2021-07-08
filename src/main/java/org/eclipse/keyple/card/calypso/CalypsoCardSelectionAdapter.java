/* **************************************************************************************
 * Copyright (c) 2021 Calypso Networks Association https://calypsonet.org/
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.calypsonet.terminal.calypso.GetDataTag;
import org.calypsonet.terminal.calypso.SelectFileControl;
import org.calypsonet.terminal.calypso.card.CalypsoCard;
import org.calypsonet.terminal.calypso.card.CalypsoCardSelection;
import org.calypsonet.terminal.card.ApduResponseApi;
import org.calypsonet.terminal.card.CardResponseApi;
import org.calypsonet.terminal.card.CardSelectionResponseApi;
import org.calypsonet.terminal.card.spi.*;
import org.eclipse.keyple.core.util.Assert;
import org.eclipse.keyple.core.util.ByteArrayUtil;

/**
 * (package-private)<br>
 * Implementation of {@link CalypsoCardSelection}.
 *
 * @since 2.0
 */
final class CalypsoCardSelectionAdapter implements CalypsoCardSelection, CardSelectionSpi {

  private static final int AID_MIN_LENGTH = 5;
  private static final int AID_MAX_LENGTH = 16;
  private static final int SW_CARD_INVALIDATED = 0x6283;

  private final List<AbstractCardCommandBuilder<? extends AbstractCardResponseParser>>
      commandBuilders;
  private final CardSelectorAdapter cardSelector;

  /**
   * (package-private)<br>
   * Creates an instance of {@link CalypsoCardSelection}.
   *
   * @since 2.0
   * @throws IllegalArgumentException If cardSelector is null.
   */
  CalypsoCardSelectionAdapter() {

    cardSelector = new CardSelectorAdapter();

    this.commandBuilders =
        new ArrayList<AbstractCardCommandBuilder<? extends AbstractCardResponseParser>>();
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public CalypsoCardSelection filterByCardProtocol(String cardProtocol) {

    Assert.getInstance().notEmpty(cardProtocol, "cardProtocol");

    cardSelector.filterByCardProtocol(cardProtocol);
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public CalypsoCardSelection filterByPowerOnData(String powerOnDataRegex) {

    Assert.getInstance().notEmpty(powerOnDataRegex, "powerOnDataRegex");

    try {
      Pattern.compile(powerOnDataRegex);
    } catch (PatternSyntaxException exception) {
      throw new IllegalArgumentException(
          String.format("Invalid regular expression: '%s'.", powerOnDataRegex));
    }

    cardSelector.filterByPowerOnData(powerOnDataRegex);
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public CalypsoCardSelection filterByDfName(byte[] aid) {

    Assert.getInstance()
        .notNull(aid, "aid")
        .isInRange(aid.length, AID_MIN_LENGTH, AID_MAX_LENGTH, "aid");

    cardSelector.filterByDfName(aid);
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public CalypsoCardSelection filterByDfName(String aid) {

    Assert.getInstance().isTrue(ByteArrayUtil.isValidHexString(aid), "aid format");

    this.filterByDfName(ByteArrayUtil.fromHex(aid));
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public CalypsoCardSelection setFileOccurrence(FileOccurrence fileOccurrence) {

    Assert.getInstance().notNull(fileOccurrence, "fileOccurrence");

    switch (fileOccurrence) {
      case FIRST:
        cardSelector.setFileOccurrence(CardSelectorSpi.FileOccurrence.FIRST);
        break;
      case LAST:
        cardSelector.setFileOccurrence(CardSelectorSpi.FileOccurrence.LAST);
        break;
      case NEXT:
        cardSelector.setFileOccurrence(CardSelectorSpi.FileOccurrence.NEXT);
        break;
      case PREVIOUS:
        cardSelector.setFileOccurrence(CardSelectorSpi.FileOccurrence.PREVIOUS);
        break;
    }
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public CalypsoCardSelection setFileControlInformation(
      FileControlInformation fileControlInformation) {

    Assert.getInstance().notNull(fileControlInformation, "fileControlInformation");

    switch (fileControlInformation) {
      case FCI:
        cardSelector.setFileControlInformation(CardSelectorSpi.FileControlInformation.FCI);
        break;
      case NO_RESPONSE:
        cardSelector.setFileControlInformation(CardSelectorSpi.FileControlInformation.NO_RESPONSE);
        break;
    }
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public CalypsoCardSelection addSuccessfulStatusWord(int statusWord) {

    Assert.getInstance().isInRange(statusWord, 0, 0xFFFF, "statusWord");

    cardSelector.addSuccessfulStatusWord(statusWord);
    return null;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public CalypsoCardSelection acceptInvalidatedCard() {
    cardSelector.addSuccessfulStatusWord(SW_CARD_INVALIDATED);
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public CalypsoCardSelection prepareReadRecordFile(byte sfi, int recordNumber) {

    Assert.getInstance()
        .isInRange((int) sfi, CalypsoCardConstant.SFI_MIN, CalypsoCardConstant.SFI_MAX, "sfi")
        .isInRange(
            recordNumber,
            CalypsoCardConstant.NB_REC_MIN,
            CalypsoCardConstant.NB_REC_MAX,
            "recordNumber");

    CardReadRecordsBuilder cardReadRecordsBuilder =
        new CardReadRecordsBuilder(
            CalypsoCardClass.ISO, sfi, recordNumber, CardReadRecordsBuilder.ReadMode.ONE_RECORD, 0);
    commandBuilders.add(cardReadRecordsBuilder);

    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public CalypsoCardSelection prepareGetData(GetDataTag tag) {
    Assert.getInstance().notNull(tag, "tag");

    // create the builder and add it to the list of commands
    switch (tag) {
      case FCI_FOR_CURRENT_DF:
        commandBuilders.add(new CardGetDataFciBuilder(CalypsoCardClass.ISO));
        break;
      case FCP_FOR_CURRENT_FILE:
        commandBuilders.add(new CardGetDataFcpBuilder(CalypsoCardClass.ISO));
        break;
      default:
        throw new UnsupportedOperationException("Unsupported Get Data tag: " + tag.name());
    }

    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public CalypsoCardSelection prepareSelectFile(byte[] lid) {

    Assert.getInstance().notNull(lid, "lid").isEqual(lid.length, 2, "lid length");

    commandBuilders.add(new CardSelectFileBuilder(CalypsoCardClass.ISO, lid));
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public CalypsoCardSelection prepareSelectFile(short lid) {
    byte[] bLid =
        new byte[] {
          (byte) ((lid >> 8) & 0xff), (byte) (lid & 0xff),
        };
    prepareSelectFile(bLid);
    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public CalypsoCardSelection prepareSelectFile(SelectFileControl selectControl) {

    Assert.getInstance().notNull(selectControl, "selectControl");

    commandBuilders.add(new CardSelectFileBuilder(CalypsoCardClass.ISO, selectControl));

    return this;
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public CardSelectionRequestSpi getCardSelectionRequest() {
    List<ApduRequestSpi> cardSelectionApduRequests = new ArrayList<ApduRequestSpi>();
    if (!commandBuilders.isEmpty()) {
      for (AbstractCardCommandBuilder<? extends AbstractCardResponseParser> commandBuilder :
          commandBuilders) {
        cardSelectionApduRequests.add(commandBuilder.getApduRequest());
      }
      return new CardSelectionRequestAdapter(
          cardSelector, new CardRequestAdapter(cardSelectionApduRequests, false));
    } else {
      return new CardSelectionRequestAdapter(cardSelector, null);
    }
  }

  /**
   * {@inheritDoc}
   *
   * @since 2.0
   */
  @Override
  public SmartCardSpi parse(CardSelectionResponseApi cardSelectionResponse) throws ParseException {

    CardResponseApi cardResponse = cardSelectionResponse.getCardResponse();

    List<ApduResponseApi> apduResponses;

    if (cardResponse != null) {
      apduResponses = cardResponse.getApduResponses();
    } else {
      apduResponses = Collections.emptyList();
    }

    if (commandBuilders.size() != apduResponses.size()) {
      throw new ParseException("Mismatch in the number of requests/responses.");
    }

    CalypsoCardAdapter calypsoCard;
    try {
      calypsoCard = new CalypsoCardAdapter();
      if (cardSelectionResponse.getSelectApplicationResponse() != null) {
        calypsoCard.initializeWithFci(cardSelectionResponse.getSelectApplicationResponse());
      } else if (cardSelectionResponse.getPowerOnData() != null) {
        calypsoCard.initializeWithPowerOnData(cardSelectionResponse.getPowerOnData());
      }

      if (!commandBuilders.isEmpty()) {
        CalypsoCardUtilAdapter.updateCalypsoCard(calypsoCard, commandBuilders, apduResponses);
      }
    } catch (Exception e) {
      throw new ParseException("Invalid card response: " + e.getMessage(), e);
    }

    if (calypsoCard.getProductType() == CalypsoCard.ProductType.UNKNOWN
        && cardSelectionResponse.getSelectApplicationResponse() == null
        && cardSelectionResponse.getPowerOnData() == null) {
      throw new ParseException(
          "Unable to create a CalypsoCard: no power-on data and no FCI provided.");
    }

    return calypsoCard;
  }
}
