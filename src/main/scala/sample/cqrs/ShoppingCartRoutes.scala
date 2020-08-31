package sample.cqrs

import scala.concurrent.Future
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.pattern.StatusReply
import akka.util.Timeout

object ShoppingCartRoutes {
  final case class AddItem(cartId: String, itemId: String, quantity: Int)
  final case class UpdateItem(cartId: String, itemId: String, quantity: Int)
}

class ShoppingCartRoutes()(implicit system: ActorSystem[_]) {

  implicit private val timeout: Timeout =
    Timeout.create(system.settings.config.getDuration("shopping.askTimeout"))
  private val sharding = ClusterSharding(system)

  import ShoppingCartRoutes._
  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  import akka.http.scaladsl.server.Directives._
  // JsonFormats内のimplicit valはここでインポートされているのか
  import JsonFormats._

  val shopping: Route =
    pathPrefix("shopping") {
      pathPrefix("carts") {
        concat(
          post {
            // POST /shopping/carts/xxx は、アイテムの追加 (AddItem）
            // as は型によってマーシャリングする。
            // def as[T](implicit um: FromRequestUnmarshaller[T]) = um
            entity(as[AddItem]) {
              data =>
                val entityRef = sharding.entityRefFor(ShoppingCart.EntityKey, data.cartId)
                val reply: Future[StatusReply[ShoppingCart.Summary]] = entityRef.ask(ShoppingCart.AddItem(data.itemId, data.quantity, _))
                onSuccess(reply) {
                  case StatusReply.Success(summary: ShoppingCart.Summary) =>
                    complete(StatusCodes.OK -> summary)
                  case StatusReply.Error(reason) =>
                    complete(StatusCodes.BadRequest -> reason)
                }
            }
          },
          put {
            // PUT //shopping/carts/xxx は、アイテムの更新（UpdateItem）
            entity(as[UpdateItem]) {
              data =>
                val entityRef =
                  sharding.entityRefFor(ShoppingCart.EntityKey, data.cartId)

                def command(replyTo: ActorRef[StatusReply[ShoppingCart.Summary]]) =
                  if (data.quantity == 0)
                    ShoppingCart.RemoveItem(data.itemId, replyTo)
                  else
                    ShoppingCart.AdjustItemQuantity(data.itemId, data.quantity, replyTo)

                val reply: Future[StatusReply[ShoppingCart.Summary]] =
                  entityRef.ask(command(_))
                onSuccess(reply) {
                  case StatusReply.Success(summary: ShoppingCart.Summary) =>
                    complete(StatusCodes.OK -> summary)
                  case StatusReply.Error(reason) =>
                    complete(StatusCodes.BadRequest -> reason)
                }
            }
          },
          pathPrefix(Segment) { cartId =>
            concat(get {
              // ショッピングカートの内容をGETする操作。sharding.entityRefFor でエンティティ参照をもらい、
              // エンティティ参照に対してShoppingCart.Getをaskしてカート内容を取得してる
              // https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html
              // entityRefは、EntityKeyにcartIdを指定して取得したRef。
              val entityRef = sharding.entityRefFor(ShoppingCart.EntityKey, cartId)
              // entityRefにaskした。ShoppingCart.Getをask
              // Getをaskすると、ShoppingCartのopenShoppingCartのcase Getに入って、state.toSummary を取得している
              // summaryは stateがcborSerializableでシリアライズされたもの。
              onSuccess(entityRef.ask(ShoppingCart.Get)) { summary =>
                if (summary.items.isEmpty) complete(StatusCodes.NotFound)
                else complete(summary)
              }
            }, path("checkout") {
              post {
                // entityRefをcartIdから作成
                val entityRef = sharding.entityRefFor(ShoppingCart.EntityKey, cartId)
                // CheckOutをaskした。
                val reply: Future[StatusReply[ShoppingCart.Summary]] = entityRef.ask(ShoppingCart.Checkout(_))
                // 結果を得られた
                onSuccess(reply) {
                  case StatusReply.Success(summary: ShoppingCart.Summary) =>
                    complete(StatusCodes.OK -> summary)
                  case StatusReply.Error(reason) =>
                    complete(StatusCodes.BadRequest -> reason)
                }
              }
            })
          })
      }
    }

}

//暗黙のパラメータはもともと、Haskellにある「型クラス」という機能を実現するために導入された機能です、という話が
//実践Scala入門にあった。その話、なのか？？
object JsonFormats {

  import spray.json.RootJsonFormat
  // import the default encoders for primitive types (Int, String, Lists etc)
  import spray.json.DefaultJsonProtocol._

  //summaryFormat, addItemFormat, updateItemFormatのいずれも、ここでimplicit valとして設定しただけで
  // このakka-sample-cqrs-scalaの中では呼ばれていない。implicit valだからか、、、

  // jsonFormat2 は、２パラメータのJSON化。RootJsonFormatはジェネリックをとり、それが ShoppingCart.Summary
  // 2パラメータ、というのが、２パラメータをとるケースクラス、というのが面白い
  implicit val summaryFormat: RootJsonFormat[ShoppingCart.Summary] = jsonFormat2(ShoppingCart.Summary)
  // jsonFormat3は、３パラメータのJSONフォーマッタ
  // AddItemとUpdateItemもジェネリックで渡しているパラメータ
  implicit val addItemFormat: RootJsonFormat[ShoppingCartRoutes.AddItem] = jsonFormat3(ShoppingCartRoutes.AddItem)
  implicit val updateItemFormat: RootJsonFormat[ShoppingCartRoutes.UpdateItem] = jsonFormat3(ShoppingCartRoutes.UpdateItem)

}
