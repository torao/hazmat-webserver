# HazMat Web Server

Asynchronous Templates & API Scripting HTTP Server for http://hazm.at/, so called Static Site Generator

## Quick Start

You can use [the Docker image](https://cloud.docker.com/swarm/torao/repository/docker/torao/hazmat-webserver/general) which you can try immediately by just creating the site directory.
Place `docroot`, `cache`, `conf` under the directory `$SERVER_HOME` and NOTE that the `cache` have to be
777 permission to write temporary data by server.

```
$ mkdir $SERVER_HOME/cache
$ chmod 777 $SERVER_HOME/cache
$ cat $SITE/docroot/index.html
<html>
...
</html>
$ cat $SERVER_HOME/conf/server.conf
server.bind-address = "0.0.0.0:8088"

$ sudo docker run --rm -it -p 8088:8088 -v $SERVER_HOME:/opt/site torao/hazmat-webserver /opt/site
$ curl http://localhost:8088/index.html
<html>
...
</html>
```

## Introduction

HazMat Web Server はテンプレートエンジンと API スクリプティングのみに特化した Web サーバ (静的サイトジェネレータ) だ。
どこかのインディアン Web サーバじゃねえし、赤錆びた線路みたいなフルスタック Web フレームワークでもねえ。
その中間を埋めるサーバだ。

メテーのサイトを構築するのにタダの HTTP サーバに静的なコンテンツじゃページごとのレイアウトを統一的に管理できなくて超不便じゃん?
CMS は便利だがスキームがブログや Wiki なんかに特化してるんで THE 俺様ホームページ向けではないしな。
かといってフルスタック Web フレームワークを持ち込んでも大ナタも甚だしいだろ。

だいたい俺くらいのレベルだとシンプルで素直な XHTML に文章を書いてサーバサイドで XSL 適用するのが一番楽なんだよ。
それに加えてサーヴァサイドの JavaScript で少々 API のようなものが組めればよい。
HazMat Server はそのために実装した特別なサーバだ。


## Startup Server

Java 8 の最新版が必要。

```
$ ./sbt "runMain at.hazm.webserver.Server $SERVER_HOME"
```

`$SERVER_HOME` はコンテンツやサーバ設定を記述したディレクトリだ。省略した場合は起動時のカレントディレクトリ `.` を指定したのと同じ意味。
`$SERVER_HOME/docroot` がサイト URL のルートとマッピングされるからトップページはここに置いておけばいい。

```
＄SERVER_HOME/
  +- conf/
      +- server.conf
  +- docroot/
      +- index.html
      +- ...
  +-- cache/
```

`$SERVER_HOME/cache` にはサーバが一時ファイルを書き込むため多くの場合 777 パーミッションを設定しなければならない。

以下 `$SERVER_HOME/docroot` を `$DOCROOT` と記す。

テンプレート処理の対象外のファイルは通常の静的ファイルと同様に要求があればそのままクライアントへ送信されるだろう。

## Configurations

`$SERVER_HOME/conf/server.conf` を参照する。構文は Typesafe Config を使用できる。

| NAME                           | DESCRIPTION  | DEFAULT |
|:-------------------------------|:-------------|:------------|
| server.request.timeout         | リクエストタイムアウト (秒) | 30 |
| server.compression-level       | 圧縮レベル | 0 |
| server.max-request-size        | リクエストの最大サイズ (B) | 500 |
| server.bind-address            | バインドアドレス | localhost:80 |
| server.send-buffer-size        | 送信バッファサイズ (B) | 4096 |
| template.update-check-interval | ファイル更新確認間隔 (秒) | 2 |
| script.timeout                 | スクリプト実行タイムアウト (ミリ秒) | 10000 |
| script.extensions              | サーバサイドスクリプトとして実行するファイルの拡張子 | .xjs |
| script.extensions-java         | サーバサイド Java として実行するファイルの拡張子 | .java |
| script.libs                    | 外部ライブラリ(複数指定可) | "" |
| redirect: { (regex: url)* }    | リダイレクト | |
| error: { (regex: path)* }      | エラーページ | |

リダイレクト

```javascript
redirect: {
  "/foo/(.*)": "/bar/$1",  // 302 Temporary Moved
  "/foo/(.*)": "!/bar/$1", // 301 Permanently Moved
}
```

エラーページ
```javascript
error: {
  "/foo/.*": "/bar.xsl"
}
```

外部ライブラリの読み込みはディレクトリ内のサブディレクトリを捜査しすべての `*.jar` を参照する。

## Templates Processing

テンプレート処理の対象かはファイルの拡張子で判断する。
例えば `$DOCROOT/foo/bar.xml` が存在する状態で `/foo/bar.html` に要求があったとしよう。
`*.xml` は XSL 処理の対象で `*.html` を出力する想定だから、この要求は XSL テンプレートエンジンを起動する。
`bar.xml` で指定された XSL を適用し、生成された HTML をあたかも `bar.html` というファイルがあるかのように応答するだろう。

`$DOCROOT/foo/bar.html` というファイルが存在しているときは `bar.xml` のテンプレート処理よりも優先されるので注意が必要だ。
また XML も XSL も公開ファイルだということに注意してほしい。例えば `/foo/bar.xml` で直接 XML を参照することができるだろう。

XSL で変換されたファイルは元の XML や XSL が更新されない限り変化しないためローカルディスク `$SERVER_HOME/cache` のどこかに保存される。
だからサーバが起動して最初のリクエストは遅いが 2 度目の要求からは静的なファイルを返すのとほぼ同じ速度となるだろう。

### Available Template Processing

| Source         | Depends        | Destination | Features      |
|:---------------|:---------------|:------------|:--------------|
| *.xml, *.xhtml | *.xml, *.xsl   | *.html      | XInclude, XSL |
| *.ts           |                | *.js        | TypeScript    |
| *.scss, *.sass |                | *.css       | SCSS          |

### XSLT Processing

_filename_.html にリクエストしたケースを考える。もし docroot 以下に該当する _filename_.html が存在しない代わりに _filename_.**xml**
があった場合、サーバは XSL Translation 処理を行った結果を _filename_.html のレスポンスとして返す。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?xsl-stylesheet type="text/xsl" href="template.xsl"?>
<html>
  <head>
    <title>Welcome My Site</title>
  </head>
  <body>
  <p>This is Sample Site.</p>
  </body>
</html>
```

以下はこの xhtml をブラウザ表示用に変換する `template.xsl` だ。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:template match="/html">
    <html lang="en">
      <xsl:apply-templates/>
    </html>
  </xsl:template>
  <xsl:template match="head">
    <head>
      <meta http-equiv="X-UA-Compatible" content="IE=edge"/>
      <meta charset="utf-8"/>
      <meta name="author" content="your name"/>
      <meta name="viewport" content="width=device-width, initial-scale=1"/>
      <link rel="shortcut icon" href="/favicon.png" type="image/png" />
      <link rel="icon" href="/favicon.png" type="image/png" />
      <link rel="stylesheet" href="/stylesheet/common.css"/>
      <xsl:copy-of select="*" />
    </head>
  </xsl:template>
  <xsl:template match="body">
    <body>
      <nav><img src="/header.png"/></nav>
      <div class="main">
        <xsl:copy-of select="node()"/>
      </div>
      <footer>Copyright &#169; 2017 your name. All Rights Reserved.</footer>
    </body>
  </xsl:template>
</xsl:stylesheet>
```

このように共通のレイアウトを XSL 側に記述することでコンテンツとなる xhtml の作成に注力することができる。
もちろん元のコンテンツは xhtml である必要はなく独自スキーマをもつ XML でもかまわない。

XSL テンプレートには `method`, `host`, `uri`, `path` がパラメータとして渡される。ただしこれらのパラメータを使用して HTML を作成した
場合、リクエスト事にその値が変化してもキャッシュが再構築されないことに注意。

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
例えば単体の文字列や数値 `"string"` は JSON で定義されていないため要素数1の配列 `["string"]` に変換されるし、
レスポンスに紛れ込んだ `undefined` と `NaN` は `null` に置き換えている。
`function` や `Date` などのオブジェクトが混じっていた場合は意図した JSON にはならないかもしれないが、いずれも
それらは対応しておらず悪いのはオメーだ。

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

### External Libraries

JavaScript の実行に外部ライブラリ (JAR) が必要であれば sbt を使うことができる。

まず以下のような `build.sbt` を作成する。

```scala
organization := "at.hazm"

name := "hazmat-contents"

version := "1.0"

scalaVersion := "2.12.3"

resolvers += "CodeLibs Repository" at "http://maven.codelibs.org/"

libraryDependencies += "org.codelibs" % "elasticsearch-analysis-kuromoji-neologd" % "5.+"

retrieveManaged := true
```

`libraryDependencies` に必要なライブラリの Maven を指定し、`sbt package` を実行すれば `lib_managed` 以下に
ライブラリが保存される。

次に設定ファイルの `script.libs` に `lib/:lib_managed/` のように JAR が保存されたディレクトリを追加する。

## Error Processing

`$DOCROOT/error/500.html` のようなファイルが存在すればそのファイルを表示する。
`$DOCROOT/error/error.xsl` が存在すればエラー状況を示す XML からレスポンスを動的に生成して返す事ができる。`error.xsl`
が処理するのは以下の XML である。

```xml
<error>
  <code>エラーコード</code>
  <phrase>レスポンスフレーズ</phrase>
  <message>エラーメッセージ</message>
</error>
```

## Docker

`sbt docker:publishLocal` でローカルの docker にイメージを作成する。
Windows の場合は Docker ターミナルから行うこと。また Docker Hub に対して `sbt docker:publish` を行う場合は先に `docker login` を行うこと。