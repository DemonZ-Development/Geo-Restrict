use waki::bindings::wasi::{
    http::{
        outgoing_handler,
        types::{Fields, Method, OutgoingBody, OutgoingRequest, RequestOptions, Scheme},
    },
    io::streams::StreamError,
};

pub fn request(
    method: Method,
    address: &str,
    token: &str,
    body: &[u8],
    timeout_ms: u64,
) -> Result<(u16, Vec<u8>), String> {
    let url = url::Url::parse(address).map_err(|_| "Invalid HTTP URL")?;
    let headers = Fields::new();
    headers
        .set(
            &"user-agent".to_string(),
            &[b"GeoRestrict/2.1.0 (Pumpkin)".to_vec()],
        )
        .map_err(|_| "Invalid header")?;
    headers
        .set(&"accept".to_string(), &[b"application/json".to_vec()])
        .map_err(|_| "Invalid header")?;
    if !token.is_empty() {
        headers
            .set(
                &"authorization".to_string(),
                &[format!("Bearer {token}").into_bytes()],
            )
            .map_err(|_| "Invalid token")?;
        headers
            .set(
                &"x-georestrict-token".to_string(),
                &[token.as_bytes().to_vec()],
            )
            .map_err(|_| "Invalid token")?;
    }
    if !body.is_empty() {
        headers
            .set(&"content-type".to_string(), &[b"application/json".to_vec()])
            .map_err(|_| "Invalid header")?;
    }
    let req = OutgoingRequest::new(headers);
    req.set_method(&method).map_err(|_| "Invalid method")?;
    req.set_scheme(Some(&if url.scheme() == "https" {
        Scheme::Https
    } else {
        Scheme::Http
    }))
    .map_err(|_| "Invalid scheme")?;
    req.set_authority(Some(
        &url[url::Position::BeforeHost..url::Position::AfterPort],
    ))
    .map_err(|_| "Invalid authority")?;
    req.set_path_with_query(Some(
        &url[url::Position::BeforePath..url::Position::AfterQuery],
    ))
    .map_err(|_| "Invalid path")?;
    let outgoing = req.body().map_err(|_| "HTTP body unavailable")?;
    let options = RequestOptions::new();
    let nanos = Some(timeout_ms.saturating_mul(1_000_000));
    options
        .set_connect_timeout(nanos)
        .map_err(|_| "Cannot set timeout")?;
    options
        .set_first_byte_timeout(nanos)
        .map_err(|_| "Cannot set timeout")?;
    options
        .set_between_bytes_timeout(nanos)
        .map_err(|_| "Cannot set timeout")?;
    let future = outgoing_handler::handle(req, Some(options))
        .map_err(|e| format!("HTTP request failed: {e:?}"))?;
    if !body.is_empty() {
        let stream = outgoing.write().map_err(|_| "Cannot write HTTP body")?;
        for part in body.chunks(4096) {
            stream
                .blocking_write_and_flush(part)
                .map_err(|_| "HTTP body write failed")?;
        }
        drop(stream);
    }
    OutgoingBody::finish(outgoing, None).map_err(|_| "Cannot finish HTTP body")?;
    let ready = match future.get() {
        Some(value) => value,
        None => {
            future.subscribe().block();
            future.get().ok_or("HTTP response unavailable")?
        }
    };
    let response = ready
        .map_err(|_| "HTTP response already consumed")?
        .map_err(|e| format!("HTTP response failed: {e:?}"))?;
    let status = response.status();
    let incoming = response
        .consume()
        .map_err(|_| "HTTP response body unavailable")?;
    let stream = incoming
        .stream()
        .map_err(|_| "HTTP response stream unavailable")?;
    let mut data = Vec::new();
    loop {
        match stream.blocking_read(4096) {
            Ok(chunk) => {
                data.extend(chunk);
                if data.len() > 65_536 {
                    return Err("HTTP response exceeds 64 KiB".into());
                }
            }
            Err(StreamError::Closed) => break,
            Err(_) => return Err("HTTP body read failed".into()),
        }
    }
    drop(stream);
    drop(incoming);
    Ok((status, data))
}
