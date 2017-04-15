# HazMat Server

Asynchronous Templates & API Scripting Server for https://hazm.at.

## Introduction

HazMat Server はテンプレートエンジンと API スクリプティングのみに特化した Web サーバだ。
どこかのインディアンみたいな HTML を返すだけの Web サーバじゃねえし、赤錆びた線路みたいなフルスタック Web フレームワークでもねえ。
その中間を埋めるサーバだ。

メテーのサイトを構築するのにタダの HTTP サーバに静的なコンテンツじゃページごとのレイアウトを統一的に管理できなくて超不便じゃん?
CMS は便利だがスキームがブログや Wiki なんかに特化してるんで THE 俺様ホームページ向けではないしな。
かといってフルスタック Web フレームワークを持ち込んでも大ナタも甚だしいだろ。

だいたい俺くらいのレベルだとシンプルで素直な XHTML に文章を書いてサーバサイドで XSL 適用するのが一番楽なんだよ。
それに加えてサーヴァサイドの JavaScript で少々 API のようなものが組めればよい。
HazMat Server はそのために実装した特別なサーバだ。


## Boot Server

```
$ sbt "runMain at.hazm.webserver.Server $SERVER_HOME"
```

`$SERVER_HOME` はコンテンツやサーバ設定を記述したディレクトリだ。省略した場合は起動時のカレントディレクトリ `.` を指定したのと同じ意味。
`$SERVER_HOME/docroot` がサイト URL のルートとマッピングされるからトップページはここに置いておけばいい。

```
＄SERVER_HOME
  +- conf
      +- server.conf
  +- docroot
      +- index.html
      +- ...
```

以下 `$SERVER_HOME/docroot` を `$DOCROOT` と記す。

テンプレート処理の対象外のファイルは通常の静的ファイルと同様に要求があればそのままクライアントへ送信されるだろう。
実にシンプルだ。

## Templates Processing

テンプレート処理の対象かはファイルの拡張子で判断する。
例えば `$DOCROOT/foo/bar.xml` が存在する状態で `/foo/bar.html` に要求があったとしよう。
`*.xml` は XSL 処理の対象で `*.html` を出力する想定だから、この要求は XSL テンプレートエンジンを起動する。
`bar.xml` で指定された XSL を適用し、生成された HTML をあたかも `bar.html` というファイルがあるかのように応答するだろう。

`$DOCROOT/foo/bar.html` というファイルが存在しているときは `bar.xml` のテンプレート処理よりも優先されるので注意が必要だ。
また XML も XSL も公開ファイルだということに注意してほしい。例えば `/foo/bar.xml` で直接 XML を参照することができるだろう。

XSL で変換されたファイルは元の XML や XSL が更新されない限り変化しないためローカルディスク `$SERVER_HOME/cache` のどこかに保存される。
だからサーバが起動して最初のリクエストは遅いが 2 度目の要求からは静的なファイルを返すのとほぼ同じ速度となるだろう。

## Scripts Processing

### Getting Started

HazMat サーバで API を実装するには HTML ファイルと同様に JavaScript ファイルを置いておくだけだ。
例えば以下のスクリプトが /api/add.xjs でアクセス可能な場所に置いてあったとする。

```javascript
(function(){
  var a = parseInt(request.query["a"]);
  var b = parseInt(request.query["b"]);
  return { result: a + b };
})();
```

このとき、リクエスト `GET /api/add.xjs?a=1&b=2` は `{"result":3}` を返すだろう。

```
$ curl "http://$SERVER/api/add.xjs?a=1&b=2"
{"result":3}
```

このように HazMat サーバのスクリプトは非常に簡単で、スクリプトファイルの評価結果を JSON で返すことに特化している。

サーバがスクリプトファイルをサーバサイドで実行するかはそのファイルの拡張子で判断する。
デフォルトは `.xjs` だが設定で変更することができるし、もちろんどのようなファイルもサーバサイドで実行しないようにもできる。

### Request

スクリプトはグローバル変数 `request` を使用してリクエスト情報にアクセスすることができる。
どのような情報が渡されているかは以下のコードを試せばよい。

```javascript
(function(){
  return request;
})();
```

リクエストパラメータ付きで呼び出された場合、そのクエリーは JS オブジェクトに変換され `request.query` で参照できる。
例えば `a=b&x=y` は `{a:["b"],x:["y"]}` のように展開されるため `request.query["a"][0]` は `”ｂ”` となるだろう。

application/json 形式の POST リクエストはさらに強力だ。
その JSON がそのまま `request.query` となるため高度に構造化されたデータをスクリプトに渡すことができる。

### Response

スクリプトファイルの評価結果がそのまま JSON としてクライアントに送信される。
即時実行関数のスタイルで記述したなら最後の `return` で返したデータがレスポンスとして送信されると思えばいい。
JSON として有効なデータであれば説明するまでもなくうまく動くが、そうでない場合は若干の訂正を行っている。
レスポンスに紛れ込んだ `undefined` と `NaN` は `null` に置き換えているし、`function` や `Date` などのオブジェクトが混じっていた
場合は意図した JSON にはならないかもしれないが、いずれも悪いのはオメーだ。

#### Error Response

文字列リテラルの引用記号がマッチしてないね。

```javascript
(function(){
  return "hell, world';
})();
```

スクリプト実行でエラーが発生すると以下の JSON を返している。ファイルと行番号が入っているのでどこの構文エラーかはすぐにわかるだろう。

```json
{
  "error": "script_error",
  "description": "javax.script.ScriptException: /api.xjs:2:23 Missing close quote\r\n  return \"hell, world';\r\n                       ^ in /api.xjs at line number 2 at column number 23"
}
```

実行時の例外は Java の例外クラス名がそのまま表れるだろう。
エラーレスポンスをカスタマイズしたければ自分で `try-catch` を使うといい。

### Mechanizm

HazMat サーバのスクリプトエンジンは Node.js なんかじゃねえ。Java 8 にバンドルされている Nashorn だ。
だから JavaScript の言語機能に加えて Java の機能がフルに使える。
あまり複雑な処理は Reflection の嵐になるのでおすすめしないがどうしても必要なら気合で乗り切れ。

```javascript
(function(){
  java.lang.println("hello, world");
})();
```

コンテキストはスクリプトファイルの実行ごとに隔離されているからスクリプト間で直接データの受け渡しすることはできない。
外部スクリプトの読み込み、外部設定、DB やファイルアクセスなどは将来的に (俺の) 需要があれば実装するかもしれない。

