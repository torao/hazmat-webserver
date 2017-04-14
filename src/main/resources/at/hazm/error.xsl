<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0">
  <xsl:output method="html" encoding="UTF-8"/>
  <xsl:template match="/">
    <html>
      <body>
        <xsl:apply-templates/>
      </body>
    </html>
  </xsl:template>
  <xsl:template match="error">
    <h1><xsl:value-of select="code"/>&#x20;<xsl:value-of select="phrase"/></h1>
    <p>
      <xsl:value-of select="message"/>
    </p>
  </xsl:template>
</xsl:stylesheet>