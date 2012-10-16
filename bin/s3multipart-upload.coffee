#!/usr/bin/coffee

fs = require "fs"
EventEmitter = require("events").EventEmitter
crypto = require "crypto"
cipherBlockSize = require 'cipher-block-size'
params = require('optimist').argv
Stream = require 'stream'
knox = require 'knox'
Parser = require('node-expat').Parser
util = require '../lib/util'
https = require 'https'

https.globalAgent.maxSockets = params['max-concurrent-connections'] if params['max-concurrent-connections']?

client = knox.createClient key: params['aws-access-key'], secret: params['aws-secret-key'], bucket: params.bucket
startMultipart = (tried) ->
  req = client.request 'POST', "/#{params.fileName}?uploads"
  req.on 'response', (res) ->
    if res.statusCode < 300
      parser = new Parser()
      uploadId = ""
      parser.on 'startElement', (name) ->
        if name.toLowerCase() is 'uploadid'
          parser.on 'text', (text) ->
            uploadId += text
          parser.on 'endElement', ->
            parser.removeAllListeners 'text'
      res.on 'end', ->
        startUpload uploadId
      res.pipe parser
    else
      console.error "Error: Response code #{res.statusCode}"
      console.error "Headers:"
      console.error require('util').inspect res.headers
      res.on 'data', (chunk) ->
        console.error chunk.toString()
      res.on 'end', ->
        process.exit 1 if tried
        startMultipart true

  req.end()

startMultipart false

startUpload = (uploadId) ->
  chunkCount = null
  completeReq = []
  encryptionFilter = (inStreamEmitter) ->
    outStreamEmitter = new EventEmitter()
    inStreamEmitter.on 'stream', (stream) ->
      cipher = crypto.createCipher params.algorithm, params.password
      newStream = new Stream()
      newStream.readable = true
      newStream.pause = -> stream.pause.call stream, arguments
      newStream.resume = -> stream.resume.call stream, arguments
      newStream.destroy = -> stream.destroy.call stream, arguments
      newStream.index = stream.index
      stream.on 'data', (data) ->
        newStream.emit 'data', cipher.update data, 'buffer', 'buffer'
      stream.on 'end', ->
        if newStream.readable
          newStream.emit 'data', cipher.final 'buffer'
          newStream.readable = false
        newStream.emit 'end'
      stream.on 'error', (exception) ->
        newStream.readable = false
        newStream.emit 'error', exception
      stream.on 'close', ->
        if newStream.readable
          newStream.emit 'data', cipher.final 'buffer'
          newStream.readable = false
        newStream.emit 'close'
      outStreamEmitter.emit 'stream', newStream
    outStreamEmitter

  hashFilter = (inStreamEmitter) ->
    outStreamEmitter = new EventEmitter()
    inStreamEmitter.on 'stream', (stream) ->
      hash = crypto.createHash 'sha256'
      newStream = new Stream()
      newStream.readable = true
      newStream.pause = -> stream.pause.call stream, arguments
      newStream.resume = -> stream.resume.call stream, arguments
      newStream.destroy = -> stream.destroy.call stream, arguments
      newStream.index = stream.index
      stream.on 'data', (data) ->
        newStream.emit 'data', data
        hash.update data, 'buffer'
      stream.on 'end', ->
        newStream.emit 'data', hash.digest 'buffer'
        newStream.readable = false
        newStream.emit 'end'
      stream.on 'error', (exception) ->
        newStream.readable = false
        newStream.emit 'error', exception
      stream.on 'close', ->
        newStream.emit 'close'
      outStreamEmitter.emit 'stream', newStream
    outStreamEmitter

  finalize = ->
    completeString = "<CompleteMultipartUpload>"
    completeString += reqPart for reqPart in completeReq
    completeString += "</CompleteMultipartUpload>"
    req = client.request 'POST', "/#{params.fileName}?uploadId=#{uploadId}", 'Content-Length': completeString.length
    req.on 'response', (res) ->
      console.log "Headers:"
      console.log require('util').inspect res.headers
      res.on 'data', (chunk) ->
        console.log chunk.toString()
      res.on 'end', ->
        process.exit
    req.end completeString

  upload = (inStreamEmitter) ->
    blockSize = cipherBlockSize params.algorithm
    streamLength = (Math.floor(params.chunkSize/blockSize) + 1) * blockSize + 32
    activeReqs = 0
    queuedReqs = []
    addActiveReq = -> activeReqs += 1
    removeActiveReq = ->
      activeReqs -= 1
      queued = queuedReqs.shift()
      if queued
        queued.resume()
        inStreamEmitter.emit 'stream', queued
    inStreamEmitter.on 'stream', (stream) ->
      onerror = (err) ->
        console.error "Error in chunk #{stream.index}: #{err}"
        chunkCount -= 1
        if stream?.req?.socket
          stream.req.socket.emit 'agentRemove'
          stream.req.socket.destroy()
        createNewReadStream stream.index * params.chunkSize
      stream.on 'error', onerror
      stream._ended = false
      if activeReqs >= https.globalAgent.maxSockets
        stream.bufArray = []
        stream.pause()
        stream.on 'data', (chunk) ->
          stream.bufArray.push chunk
        stream.on 'end', ->
          stream._ended = true
        queuedReqs.push stream
      else
        addActiveReq()
        remoteHash = null
        localHash = null
        hashesDone = ->
          throw "Bad hash" unless util.memcmp(localHash, remoteHash)
          completeReq[stream.index] = "<Part><PartNumber>#{stream.index + 1}</PartNumber><ETag>#{remoteHash.toString 'hex'}</ETag></Part>"
          chunkCount -= 1
          if chunkCount is 0
            finalize()
        hash = crypto.createHash 'md5'
        req = client.put "/#{params.fileName}?partNumber=#{stream.index + 1}&uploadId=#{uploadId}",
          {'Content-Length': streamLength, Connection: 'keep-alive' }
        stream.req = req
        req.on 'error', (err) ->
          removeActiveReq()
          onerror err
        req.on 'socket', (socket) ->
          req.socket = socket
          socket.removeAllListeners 'error'
          socket.on 'error', (err) -> req.emit 'error', err
          socket.resume()
        req.on 'response', (res) ->
          removeActiveReq()
          if res.statusCode < 300
            res.on 'error', ->
            remoteHash = new Buffer(res.headers.etag.slice(1,33), 'hex')
            hashesDone() unless localHash is null
          else
            err = "Error: Response code #{res.statusCode}\n"
            err += "Headers:\n"
            err += require('util').inspect res.headers
            err += "\n"
            res.on 'data', (chunk) ->
              err += chunk.toString()
              err += "\n"
            res.on 'end', ->
              onerror err
            res.on 'error', (err) ->
              onerror err
        if stream?.bufArray
          for chunk in stream.bufArray
            req.write chunk
            hash.update chunk, 'buffer'
        if stream._ended
          req.end()
          localHash = hash.digest 'buffer'
        else
          stream.removeAllListeners 'data'
          stream.removeAllListeners 'end'
          stream.on 'data', (chunk) ->
            hash.update chunk, 'buffer'
          stream.on 'end', ->
            localHash = hash.digest 'buffer'
            hashesDone() unless remoteHash is null
          stream.pipe req

  createNewReadStream = ->

  fs.open params.file, 'r', (err, fd) ->
    throw err if err
    fs.fstat fd, (err, stats) ->
      throw err if err
      initialStreamEmitter = new EventEmitter()
      createNewReadStream = (pos) ->
        chunkCount += 1
        newStream = fs.createReadStream params.file,
          fd: fd,
          start: pos,
          end: Math.min(pos + params.chunkSize - 1, stats.size - 1)
        newStream.destroy = -> @readable = false
        newStream.index = pos / params.chunkSize
        if pos + params.chunkSize < stats.size
          initialStreamEmitter.emit 'stream', newStream
        else
          finalStream = new Stream()
          finalStream.pause = -> newStream.pause.call newStream, arguments
          finalStream.resume = -> newStream.resume.call newStream, arguments
          finalStream.destroy = -> newStream.destroy.call newStream, arguments
          finalStream.index = newStream.index
          finalStream.readable = true
          newStream.on 'data', (data) ->
            finalStream.emit 'data', data
          newStream.on 'end', ->
            if finalStream.readable
              pad = new Buffer(params.chunkSize - (stats.size - pos))
              pad.fill 0
              finalStream.emit 'data', pad
              finalStream.readable = false
            finalStream.emit 'end'
          newStream.on 'error', (exception) ->
            finalStream.readable = false
            finalStream.emit 'error', exception
          newStream.on 'close', ->
            finalStream.readable = false
            finalStream.emit 'close'
          initialStreamEmitter.emit 'stream', finalStream
      process.nextTick ->
        for pos in [0..stats.size - 1] by params.chunkSize
          createNewReadStream pos
      upload hashFilter encryptionFilter initialStreamEmitter
