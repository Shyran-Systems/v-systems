package scorex.block

import com.google.common.primitives.{Bytes, Ints, Longs}
import org.joda.time.DateTime
import play.api.libs.json.Json
import scorex.account.{PrivateKeyAccount, PublicKeyAccount}
import scorex.consensus.ConsensusModule
import scorex.crypto.EllipticCurveImpl
import scorex.transaction.TransactionModule
import scorex.utils.ScorexLogging

import scala.util.{Failure, Try}

/**
  * A block is a an atomic piece of data network participates are agreed on.
  *
  * A block has:
  * - transactions data: a sequence of transactions, where a transaction is an atomic state update.
  * Some metadata is possible as well(transactions Merkle tree root, state Merkle tree root etc).
  *
  * - consensus data to check whether block was generated by a right party in a right way. E.g.
  * "baseTarget" & "generatorSignature" fields in the Nxt block structure, nonce & difficulty in the
  * Bitcoin block structure.
  *
  * - a signature(s) of a block generator(s)
  *
  * - additional data: block structure version no, timestamp etc
  */

trait Block extends ScorexLogging {
  type ConsensusDataType
  type TransactionDataType

  implicit val consensusModule: ConsensusModule[ConsensusDataType]
  implicit val transactionModule: TransactionModule[TransactionDataType]

  val consensusDataField: BlockField[ConsensusDataType]
  val transactionDataField: BlockField[TransactionDataType]

  val versionField: ByteBlockField
  val timestampField: LongBlockField
  val referenceField: BlockIdField
  val signerDataField: SignerDataBlockField


  // Some block characteristic which is uniq for a block
  // e.g. hash or signature. Used in referencing
  val uniqueId: Block.BlockId

  lazy val transactions = transactionModule.transactions(this)

  lazy val fee = consensusModule.feesDistribution(this).values.sum

  lazy val json =
    versionField.json ++
      timestampField.json ++
      referenceField.json ++
      consensusDataField.json ++
      transactionDataField.json ++
      signerDataField.json ++
      Json.obj(
        "fee" -> fee
      )

  lazy val bytes = {
    val txBytesSize = transactionDataField.bytes.length
    val txBytes = Bytes.ensureCapacity(Ints.toByteArray(txBytesSize), 4, 0) ++ transactionDataField.bytes

    val cBytesSize = consensusDataField.bytes.length
    val cBytes = Bytes.ensureCapacity(Ints.toByteArray(cBytesSize), 4, 0) ++ consensusDataField.bytes

    versionField.bytes ++
      timestampField.bytes ++
      referenceField.bytes ++
      cBytes ++
      txBytes ++
      signerDataField.bytes
  }

  lazy val bytesWithoutSignature = bytes.dropRight(EllipticCurveImpl.SignatureLength)

  def isValid: Boolean = {
    val v = consensusModule.isValid(this) &&
      transactionModule.isValid(this) &&
      transactionModule.blockStorage.history.contains(referenceField.value) &&
      EllipticCurveImpl.verify(signerDataField.value.signature,
        bytesWithoutSignature,
        signerDataField.value.generator.publicKey)
    if (!v) log.debug(
      s"Block checks: ${consensusModule.isValid(this)} && ${transactionModule.isValid(this)} && " +
        s"${transactionModule.blockStorage.history.contains(referenceField.value)} && " +
        EllipticCurveImpl.verify(signerDataField.value.signature, bytesWithoutSignature,
          signerDataField.value.generator.publicKey)
    )
    v
  }

  override def equals(obj: scala.Any): Boolean = {
    import shapeless.Typeable._
    obj.cast[Block].exists(_.uniqueId.sameElements(this.uniqueId))
  }
}


object Block extends ScorexLogging {
  type BlockId = Array[Byte]

  val BlockIdLength = EllipticCurveImpl.SignatureLength

  def parse[CDT, TDT](bytes: Array[Byte])
                     (implicit consModule: ConsensusModule[CDT],
                      transModule: TransactionModule[TDT]): Try[Block] = Try {

    require(consModule != null)
    require(transModule != null)

    val version = bytes.head

    var position = 1

    val timestamp = Longs.fromByteArray(bytes.slice(position, position + 8))
    position += 8

    val reference = bytes.slice(position, position + Block.BlockIdLength)
    position += BlockIdLength

    val cBytesLength = Ints.fromByteArray(bytes.slice(position, position + 4))
    position += 4
    val cBytes = bytes.slice(position, position + cBytesLength)
    val consBlockField = consModule.parseBlockData(cBytes).get
    position += cBytesLength


    val tBytesLength = Ints.fromByteArray(bytes.slice(position, position + 4))
    position += 4
    val tBytes = bytes.slice(position, position + tBytesLength)
    val txBlockField = transModule.parseBlockData(tBytes).get
    position += tBytesLength

    val genPK = bytes.slice(position, position + EllipticCurveImpl.KeyLength)
    position += EllipticCurveImpl.KeyLength

    val signature = bytes.slice(position, position + EllipticCurveImpl.SignatureLength)

    new Block {
      override type ConsensusDataType = CDT
      override type TransactionDataType = TDT

      override val transactionDataField: BlockField[TransactionDataType] = txBlockField

      override implicit val consensusModule: ConsensusModule[ConsensusDataType] = consModule
      override implicit val transactionModule: TransactionModule[TransactionDataType] = transModule

      override val versionField: ByteBlockField = ByteBlockField("version", version)
      override val referenceField: BlockIdField = BlockIdField("reference", reference)
      override val signerDataField: SignerDataBlockField =
        SignerDataBlockField("signature", SignerData(new PublicKeyAccount(genPK), signature))

      override val consensusDataField: BlockField[ConsensusDataType] = consBlockField

      //todo: more generic approach!
      override val uniqueId: BlockId = signature

      override val timestampField: LongBlockField = LongBlockField("timestamp", timestamp)
    }
  }.recoverWith { case t: Throwable =>
    log.error("Error when parsing block", t)
    t.printStackTrace()
    Failure(t)
  }

  def build[CDT, TDT](version: Byte,
                      timestamp: Long,
                      reference: BlockId,
                      consensusData: CDT,
                      transactionData: TDT,
                      generator: PublicKeyAccount,
                      signature: Array[Byte])
                     (implicit consModule: ConsensusModule[CDT],
                      transModule: TransactionModule[TDT]): Block = {
    new Block {
      override type ConsensusDataType = CDT
      override type TransactionDataType = TDT

      override implicit val transactionModule: TransactionModule[TDT] = transModule
      override implicit val consensusModule: ConsensusModule[CDT] = consModule

      override val versionField: ByteBlockField = ByteBlockField("version", version)

      override val transactionDataField: BlockField[TDT] = transModule.formBlockData(transactionData)

      override val referenceField: BlockIdField = BlockIdField("reference", reference)
      override val signerDataField: SignerDataBlockField = SignerDataBlockField("signature", SignerData(generator, signature))
      override val consensusDataField: BlockField[CDT] = consensusModule.formBlockData(consensusData)

      override val uniqueId: BlockId = signature //todo:wrong

      override val timestampField: LongBlockField = LongBlockField("timestamp", timestamp)
    }
  }

  def buildAndSign[CDT, TDT](version: Byte,
                             timestamp: Long,
                             reference: BlockId,
                             consensusData: CDT,
                             transactionData: TDT,
                             signer: PrivateKeyAccount)
                            (implicit consModule: ConsensusModule[CDT],
                             transModule: TransactionModule[TDT]): Block = {
    val nonSignedBlock = build(version, timestamp, reference, consensusData, transactionData, signer, Array())
    val toSign = nonSignedBlock.bytes
    val signature = EllipticCurveImpl.sign(signer, toSign)
    build(version, timestamp, reference, consensusData, transactionData, signer, signature)
  }

  def genesis[CDT, TDT]()(implicit consModule: ConsensusModule[CDT],
                          transModule: TransactionModule[TDT]): Block = new Block {
    override type ConsensusDataType = CDT
    override type TransactionDataType = TDT

    override implicit val transactionModule: TransactionModule[TDT] = transModule
    override implicit val consensusModule: ConsensusModule[CDT] = consModule

    override val versionField: ByteBlockField = ByteBlockField("version", 1)
    override val transactionDataField: BlockField[TDT] = transactionModule.genesisData
    override val referenceField: BlockIdField = BlockIdField("reference", Array.fill(BlockIdLength)(0: Byte))
    override val consensusDataField: BlockField[CDT] = consensusModule.genesisData
    override val uniqueId: BlockId = Array.fill(BlockIdLength)(0: Byte)

    //todo: inject timestamp from settings
    override val timestampField: LongBlockField = LongBlockField("timestamp",
      new DateTime(System.currentTimeMillis()).toDateMidnight.getMillis)

    override val signerDataField: SignerDataBlockField =
      new SignerDataBlockField("signature", SignerData(new PublicKeyAccount(Array.fill(32)(0)), Array.fill(EllipticCurveImpl.SignatureLength)(0)))
  }
}